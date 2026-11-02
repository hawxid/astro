package id.hawx.astro.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import id.hawx.astro.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Driving GPS gaya GPS Locker, sebagai foreground service.
 *
 * Alasan service (bukan sekadar request dari fragment): request lokasi
 * dari app yang sedang background di-throttle Android — itulah yang
 * membuat publisher Kismet tidak pernah lock saat Astro ditinggal ke
 * Maps/terminal (temuan 2026-11-02: GPS Locker yang foreground-lah
 * yang akhirnya mengunci engine, lalu rantai Kismet ikut lock).
 * Foreground service + request agresif (0 ms / 0 m) membuat mesin GPS
 * di-drive terus selama driving ON, apa pun app yang sedang dibuka.
 *
 * Service ini HANYA drive + ukur (tidak menyentuh gpsd/UDP sama
 * sekali): rantai Kismet (NmeaPublisher -> gpsd) tetap independen,
 * dan begitu engine lock, publisher Kismet ikut menerima kalimat
 * fix yang sama — komposisi, bukan driver ganda.
 *
 * Komunikasi ke UI: tidak pakai Binder — fragment membaca snapshot
 * statis LAST_SNAPSHOT dan menerima broadcast tick ACTION_SNAPSHOT
 * (tanpa extra; tick = "baca LAST_SNAPSHOT sekarang"). Pola ini
 * menghindari Parcelable dan binding lifecycle.
 *
 * NMEA yang diparse: GSV (satelit in-view + SNR + azimuth/elevasi)
 * dan GSA (daftar satelit used + tipe fix). Talker GP/GL/GA/GB/GQ
 * dibedakan (kunci = talker+PRN) supaya PRN yang sama di konstelasi
 * berbeda tidak tertukar; GSA bertalker GN (gabungan) menandai used
 * berdasarkan PRN saja.
 */
public class GpsLockService extends Service {

    public static final String ACTION_START =
            "id.hawx.astro.gps.START";
    public static final String ACTION_STOP =
            "id.hawx.astro.gps.STOP";
    public static final String ACTION_SNAPSHOT =
            "id.hawx.astro.gps.SNAPSHOT";

    /** State fix. */
    public static final int STATE_OFF = 0;
    public static final int STATE_SEARCHING = 1;
    public static final int STATE_2D = 2;
    public static final int STATE_3D = 3;

    private static final String CHANNEL_ID = "astro_gps";
    private static final int NOTIF_ID = 9101;
    private static final long BROADCAST_MIN_MS = 500L;

    /** True selama driving ON (dipegang service, bukan fragment). */
    public static volatile boolean RUNNING = false;
    /** Snapshot terakhir (null bila service belum pernah jalan). */
    public static volatile Snapshot LAST_SNAPSHOT = null;

    /** Satu satelit dari kalimat GSV (+ flag used dari GSA). */
    public static final class Sat {
        public final String key;
        public final String prn;
        public final String constellation;
        public final int elevation;
        public final int azimuth;
        /** SNR dB-Hz, -1 bila kalimat tidak membawa SNR. */
        public final int snr;
        public final boolean used;

        public Sat(String key, String prn, String constellation,
                int elevation, int azimuth, int snr, boolean used) {
            this.key = key;
            this.prn = prn;
            this.constellation = constellation;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.snr = snr;
            this.used = used;
        }
    }

    /** Status GPS sekali baca (immutable, diganti utuh tiap update). */
    public static final class Snapshot {
        public final int state;
        public final double lat;
        public final double lon;
        public final double altitude;
        public final float speedKmh;
        public final float accuracyM;
        public final int used;
        public final int inView;
        /** Detik menuju first fix, -1 bila belum fix. */
        public final long ttffSec;
        /** Detik sejak driving mulai. */
        public final long elapsedSec;
        public final List<Sat> sats;

        public Snapshot(int state, double lat, double lon, double altitude,
                float speedKmh, float accuracyM, int used, int inView,
                long ttffSec, long elapsedSec, List<Sat> sats) {
            this.state = state;
            this.lat = lat;
            this.lon = lon;
            this.altitude = altitude;
            this.speedKmh = speedKmh;
            this.accuracyM = accuracyM;
            this.used = used;
            this.inView = inView;
            this.ttffSec = ttffSec;
            this.elapsedSec = elapsedSec;
            this.sats = sats;
        }
    }

    private LocationManager locationManager;
    private HandlerThread thread;
    private OnNmeaMessageListener nmeaListener;
    private LocationListener locationListener;
    private boolean driving;

    private final Map<String, Sat> sats = new LinkedHashMap<>();
    private final java.util.Set<String> usedTalkerKeys = new java.util.HashSet<>();
    private final java.util.Set<String> usedPrns = new java.util.HashSet<>();
    private int gsaFix;
    private Location lastLocation;
    private long lockStartMs;
    private long firstFixMs;
    private long lastBroadcastMs;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopDriving();
            return START_NOT_STICKY;
        }
        startDriving();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopDriving();
        super.onDestroy();
    }

    /**
     * Mulai driving. Idempotent. Gagal start (izin/GPS mati/service
     * ditolak sistem) -> service berhenti sendiri + snapshot OFF,
     * tidak pernah throw ke pemanggil.
     */
    private void startDriving() {
        if (driving) return;
        driving = true;
        RUNNING = true;
        lockStartMs = System.currentTimeMillis();
        firstFixMs = 0L;
        gsaFix = 0;
        lastLocation = null;
        synchronized (sats) {
            sats.clear();
        }
        usedTalkerKeys.clear();
        usedPrns.clear();
        lastBroadcastMs = 0L;
        try {
            createChannel();
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            // startForeground ditolak (aturan FGS Android 12+):
            // jangan biarkan service setengah jalan.
            stopDriving();
            return;
        }
        locationManager = (LocationManager) getSystemService(
                Context.LOCATION_SERVICE);
        if (locationManager == null) {
            stopDriving();
            return;
        }
        // Thread dulu, listener kemudian (pola NmeaPublisher):
        // body listener hanya membaca field saat callback tiba.
        thread = new HandlerThread("gps-lock");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        nmeaListener = (message, timestamp) -> {
            if (!driving || message == null) return;
            parseNmea(message);
            maybeBroadcast();
        };
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (!driving) return;
                lastLocation = location;
                if (firstFixMs == 0L) {
                    firstFixMs = System.currentTimeMillis();
                }
                maybeBroadcast();
            }

            @Override
            public void onStatusChanged(String provider, int status,
                    Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }
        };
        try {
            locationManager.addNmeaListener(nmeaListener, handler);
            // Request AGRESIF (0 ms / 0 m, gaya GPS Locker): tugas
            // request ini hanya men-drive mesin; fix dibaca dari
            // NMEA + Location, bukan dari throttling callback.
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f,
                    locationListener, thread.getLooper());
        } catch (SecurityException e) {
            stopDriving();
            return;
        } catch (RuntimeException e) {
            stopDriving();
            return;
        }
        publishSnapshot();
    }

    /** Hentikan driving. Best-effort, tidak pernah throw. */
    private void stopDriving() {
        if (!driving && !RUNNING) return;
        driving = false;
        RUNNING = false;
        try {
            if (locationManager != null && nmeaListener != null) {
                locationManager.removeNmeaListener(nmeaListener);
            }
        } catch (Exception ignored) {
        }
        nmeaListener = null;
        try {
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception ignored) {
        }
        locationListener = null;
        try {
            if (thread != null) thread.quitSafely();
        } catch (Exception ignored) {
        }
        thread = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        LAST_SNAPSHOT = new Snapshot(STATE_OFF, 0, 0, 0, 0, 0, 0, 0,
                -1, 0, Collections.emptyList());
        sendBroadcast(new Intent(ACTION_SNAPSHOT));
        stopSelf();
    }

    /**
     * Perintah update data A-GPS (XTRA + time injection) — mekanisme
     * standar LocationManager.sendExtraCommand yang dipakai aplikasi
     * GPS Locker sejenis. Butuh izin manifest
     * ACCESS_LOCATION_EXTRA_COMMANDS (bila tidak ada, sistem menolak
     * dengan SecurityException -> hasil "denied").
     *
     * KEJUJURAN: nilai balik true = perintah DITERIMA sistem, BUKAN
     * bukti unduhan XTRA berhasil — tidak ada API yang melaporkan itu.
     * Verifikasi perangkat hanya lewat TTFF cold start berikutnya
     * yang (bila XTRA masuk) terasa lebih cepat.
     *
     * @return "accepted" | "rejected" | "denied" | "error: ..."
     */
    public static String forceAgpsUpdate(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(
                Context.LOCATION_SERVICE);
        if (lm == null) return "error: LocationManager tidak tersedia";
        try {
            boolean xtra = lm.sendExtraCommand(
                    LocationManager.GPS_PROVIDER,
                    "force_xtra_injection", null);
            boolean time = lm.sendExtraCommand(
                    LocationManager.GPS_PROVIDER,
                    "force_time_injection", null);
            return (xtra || time) ? "accepted" : "rejected";
        } catch (SecurityException e) {
            return "denied";
        } catch (Exception e) {
            String msg = e.getMessage();
            return "error: " + (msg != null ? msg : e.toString());
        }
    }

    /** Parse satu kalimat NMEA (GSV/GSA saja; sisanya diabaikan). */
    private void parseNmea(String message) {
        String line = message.trim();
        if (!line.startsWith("$") || line.length() < 6) return;
        String talker = line.substring(1, 3);
        String type = line.substring(3, 6);
        // Buang checksum *XX dari field terakhir.
        int star = line.indexOf('*');
        if (star > 0) line = line.substring(0, star);
        String[] f = line.split(",", -1);
        if ("GSV".equals(type)) {
            parseGsv(talker, f);
        } else if ("GSA".equals(type)) {
            parseGsa(talker, f);
        }
    }

    private void parseGsv(String talker, String[] f) {
        if (f.length < 4) return;
        // Grup 4 field per satelit mulai indeks 4.
        synchronized (sats) {
            for (int i = 4; i + 3 < f.length; i += 4) {
                String prn = f[i];
                if (prn == null || prn.isEmpty()) continue;
                int elev = parseInt(f[i + 1], -1);
                int azim = parseInt(f[i + 2], -1);
                int snr = parseInt(f[i + 3], -1);
                String key = talker + prn;
                boolean used = usedTalkerKeys.contains(key)
                        || usedPrns.contains(prn);
                sats.put(key, new Sat(key, prn, constellationName(talker),
                        elev, azim, snr, used));
            }
        }
    }

    private void parseGsa(String talker, String[] f) {
        if (f.length < 3) return;
        gsaFix = parseInt(f[2], 0);
        // Field 3..14 = PRN used (bisa kosong).
        boolean combined = "GN".equals(talker);
        for (int i = 3; i < f.length && i <= 14; i++) {
            String prn = f[i];
            if (prn == null || prn.isEmpty()) continue;
            if (combined) {
                usedPrns.add(prn);
            } else {
                usedTalkerKeys.add(talker + prn);
            }
        }
        // Tandai ulang used pada sat yang sudah dikenal.
        synchronized (sats) {
            for (Map.Entry<String, Sat> e : sats.entrySet()) {
                Sat s = e.getValue();
                boolean used = usedTalkerKeys.contains(s.key)
                        || usedPrns.contains(s.prn);
                if (used != s.used) {
                    e.setValue(new Sat(s.key, s.prn, s.constellation,
                            s.elevation, s.azimuth, s.snr, used));
                }
            }
        }
    }

    private static String constellationName(String talker) {
        switch (talker) {
            case "GP":
                return "GPS";
            case "GL":
                return "GLONASS";
            case "GA":
                return "Galileo";
            case "GB":
            case "BD":
                return "BeiDou";
            case "GQ":
                return "QZSS";
            default:
                return talker;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Broadcast tick + update notifikasi, dibatasi 500 ms. */
    private void maybeBroadcast() {
        long now = System.currentTimeMillis();
        if (now - lastBroadcastMs < BROADCAST_MIN_MS) return;
        lastBroadcastMs = now;
        publishSnapshot();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
        sendBroadcast(new Intent(ACTION_SNAPSHOT));
    }

    /** Susun snapshot dari state terakhir (dipanggil thread NMEA). */
    private void publishSnapshot() {
        int state;
        if (!driving) {
            state = STATE_OFF;
        } else if (gsaFix == 3) {
            state = STATE_3D;
        } else if (gsaFix == 2) {
            state = STATE_2D;
        } else if (lastLocation != null) {
            state = STATE_3D;
        } else {
            state = STATE_SEARCHING;
        }
        double lat = 0;
        double lon = 0;
        double alt = 0;
        float speedKmh = 0;
        float accuracyM = 0;
        if (lastLocation != null) {
            lat = lastLocation.getLatitude();
            lon = lastLocation.getLongitude();
            alt = lastLocation.getAltitude();
            speedKmh = lastLocation.getSpeed() * 3.6f;
            accuracyM = lastLocation.hasAccuracy()
                    ? lastLocation.getAccuracy() : 0;
        }
        int used = 0;
        List<Sat> copy;
        synchronized (sats) {
            copy = new ArrayList<>(sats.values());
        }
        for (Sat s : copy) {
            if (s.used) used++;
        }
        long now = System.currentTimeMillis();
        long ttff = firstFixMs > 0
                ? (firstFixMs - lockStartMs) / 1000L : -1L;
        long elapsed = driving ? (now - lockStartMs) / 1000L : 0L;
        Collections.sort(copy, (a, b) -> {
            int c = a.constellation.compareTo(b.constellation);
            if (c != 0) return c;
            try {
                return Integer.parseInt(a.prn) - Integer.parseInt(b.prn);
            } catch (NumberFormatException e) {
                return a.prn.compareTo(b.prn);
            }
        });
        LAST_SNAPSHOT = new Snapshot(state, lat, lon, alt, speedKmh,
                accuracyM, used, copy.size(), ttff, elapsed,
                Collections.unmodifiableList(copy));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "GPS Astro", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Snapshot s = LAST_SNAPSHOT;
        String text;
        if (s == null || s.state == STATE_OFF) {
            text = "GPS tidak aktif";
        } else if (s.state == STATE_SEARCHING) {
            text = String.format(Locale.US, "Mencari sinyal… %ds",
                    s.elapsedSec);
        } else {
            String fix = s.state == STATE_3D ? "Lock 3D" : "Lock 2D";
            text = String.format(Locale.US, "%s · %d satelit · ±%.0f m",
                    fix, s.used, s.accuracyM);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Astro")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_gps)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
