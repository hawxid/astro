package id.hawx.astro.kismet;

import android.content.Context;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Publisher NMEA Android -> gpsd chroot (tahap B jembatan GPS Kismet).
 *
 * Rantai yang dibuktikan PoC tahap A (2026-11-02): gpsd menyajikan fix
 * valid di TCP 2947 -> Kismet konek (JSON mode) -> WebUI "GPS locked
 * (3d)" + baris avg_lat/avg_lon di KismetDB. Yang kurang di tahap A
 * hanyalah sumber fix-nya (dulu gpsfake replay); kelas ini mengganti
 * gpsfake dengan GPS hardware HP sendiri.
 *
 * Mekanisme (pola NetHunter yang terbukti: Android GPS Publisher ->
 * UDP 10110 -> gpsd):
 * - Kalimat NMEA MENTAH dari hardware GPS (OnNmeaMessageListener,
 *   API 24+; minSdk app = 24, jadi tanpa guard versi) diteruskan
 *   VERBATIM — tidak pernah disintesis (tidak ada GGA/RMC buatan).
 *   Satu-satunya normalisasi: akhir baris diseragamkan ke \r\n
 *   (standar NMEA serial) karena Android bisa mengirim tanpa itu;
 *   isi kalimat tidak diubah.
 * - Setiap kalimat = satu datagram UDP ke 127.0.0.1:10110. Chroot
 *   berbagi network namespace Android, jadi localhost mencapai
 *   listener gpsd di chroot (bukti: Kismet chroot konek ke gpsd
 *   di ::1:2947 pada PoC — namespace yang sama).
 * - Sisi chroot, gpsd membaca feed itu LEWAT URL udp:// DOKUMENTER
 *   (gpsd(8): "UDP feed — A URI with the prefix udp:// ... The
 *   daemon will open a socket listening for UDP datagrams ...
 *   interpreted as though they had been issued by a serial
 *   device. Example: udp://127.0.0.1:5000"). Karena itu TIDAK
 *   perlu feeder PTY (fallback yang disiapkan untuk gpsfake):
 *   udp:// adalah mekanisme first-class gpsd, bukan tebakan, dan
 *   menghilangkan satu proses + satu pasang PTY dari rantai.
 *
 * Lifecycle: HANYA dimiliki KismetManager (start() memanggil
 * start(), stop() memanggil stop()). stop() best-effort, tidak
 * pernah throw. Callback NMEA jalan di HandlerThread sendiri
 * (bukan main thread) supaya kirim UDP tidak mengganggu UI.
 * Threading: start()/stop() dipanggil dari thread latar manager;
 * pengiriman UDP terjadi di thread NMEA (satu thread, tanpa
 * lock tambahan selain flag volatile + null-check socket).
 */
public final class NmeaPublisher {

    /** Tujuan UDP di chroot (listener gpsd udp://). */
    private static final String UDP_HOST = "127.0.0.1";
    /** Port publisher NetHunter (10110). */
    private static final int UDP_PORT = 10110;

    private final Context appContext;

    private LocationManager locationManager;
    private DatagramSocket socket;
    private InetAddress target;
    private HandlerThread thread;
    private OnNmeaMessageListener listener;
    private volatile boolean running;

    public NmeaPublisher(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Mulai publisher. Melempar IOException bila LocationManager
     * tidak ada, tidak ada provider lokasi yang menyala, atau
     * listener/socket gagal dipasang (termasuk SecurityException
     * bila izin lokasi ternyata belum diberikan — manager sudah
     * menggerbang izin sebelum memanggil ini, ini jaring kedua).
     * Idempotent: start kedua tanpa stop diabaikan.
     */
    public synchronized void start() throws IOException {
        if (running) return;
        locationManager = (LocationManager) appContext.getSystemService(
                Context.LOCATION_SERVICE);
        if (locationManager == null) {
            throw new IOException("LocationManager tidak tersedia");
        }
        if (!anyProviderEnabled()) {
            throw new IOException("Layanan lokasi tidak aktif");
        }
        try {
            socket = new DatagramSocket();
            target = InetAddress.getByName(UDP_HOST);
        } catch (IOException e) {
            throw new IOException("Socket UDP publisher gagal: " + e.getMessage());
        }
        // Thread dulu, listener kemudian, addNmeaListener terakhir:
        // body listener hanya membaca field saat callback tiba
        // (bukan saat inisialisasi) — tidak ada self-reference
        // dalam initializer (lihat SKILL definite-assignment).
        thread = new HandlerThread("nmea-publisher");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        listener = (message, timestamp) -> {
            if (!running) return;
            DatagramSocket s = socket;
            InetAddress t = target;
            if (s == null || t == null) return;
            if (message == null || message.isEmpty()) return;
            try {
                String line = message;
                // Seragamkan akhir baris ke \r\n (standar NMEA);
                // isi kalimat tidak diubah sama sekali.
                String stripped = line.replaceAll("[\\r\\n]+$", "");
                if (stripped.isEmpty()) return;
                byte[] data = (stripped + "\r\n").getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(data, data.length, t, UDP_PORT));
            } catch (Exception ignored) {
                // Kirim gagal (socket ditutup saat stop / jaringan):
                // kalimat berikutnya dicoba lagi; tidak pernah throw
                // ke LocationManager.
            }
        };
        try {
            locationManager.addNmeaListener(listener, handler);
        } catch (SecurityException e) {
            stopQuietly();
            throw new IOException("Izin lokasi belum diberikan");
        } catch (RuntimeException e) {
            stopQuietly();
            throw new IOException("Listener NMEA gagal: " + e.getMessage());
        }
        running = true;
    }

    /**
     * Hentikan publisher. Best-effort, tidak pernah throw:
     * lepas listener, tutup socket, matikan thread. Aman dipanggil
     * berkali-kali dan tanpa start sebelumnya.
     */
    public synchronized void stop() {
        running = false;
        stopQuietly();
    }

    /** GPS_PROVIDER dulu, fallback NETWORK_PROVIDER (pola B6 GpsLive). */
    private boolean anyProviderEnabled() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Bersihkan listener/socket/thread tanpa throw (dipakai stop + gagal start). */
    private void stopQuietly() {
        try {
            if (locationManager != null && listener != null) {
                locationManager.removeNmeaListener(listener);
            }
        } catch (Exception ignored) {
        }
        listener = null;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        target = null;
        try {
            if (thread != null) thread.quitSafely();
        } catch (Exception ignored) {
        }
        thread = null;
    }
}
