package id.hawx.astro.vnc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

import id.hawx.astro.utils.Core;

/**
 * Suara desktop lokal (2026-11-05): penerima audio layar X ke speaker HP.
 *
 * Model (cermin Termux-Desktops, diadaptasi karena Termux sudah dihapus):
 * PulseAudio TIDAK jalan sebagai server di sisi Android; yang jalan di
 * chroot adalah server PulseAudio (auto_null + monitor), dan modul
 * module-simple-protocol-tcp-nya menyajikan monitor itu sebagai PCM
 * mentah lewat socket TCP. Astro di sini adalah KLIEN: terhubung ke
 * socket itu dan memainkan PCM-nya lewat AudioTrack. Jalur RTP ke VLC
 * DIPARKIR (sender terbukti jalan via tcpdump, tapi VLC tidak mau
 * memainkan stream L16 — bare rtp:// maupun SDP sama-sama sunyi).
 *
 * Kenapa (b) module-simple-protocol-tcp, bukan (a) parec | python:
 *  - Tanpa install paket chroot baru (modul ini bagian dari pulseaudio
 *    yang sudah terpasang; parec juga ada, tapi (a) butuh skrip python
 *    sender yang di-stage + dua proses (parec + python) + reconnect
 *    manual — lebih banyak bagian bergerak).
 *  - Modul ini server TCP bawaan PA: menerima klien, menangani
 *    disconnect, dan hanya mengalirkan audio saat ada klien yang
 *    terhubung (on-demand secara alami).
 *  - Sisi Android jadi klien TCP biasa (connect + baca) — lebih
 *    sederhana daripada server socket (tanpa accept loop / bind).
 *  - Tahan urutan start mana pun: ON memuat modul dulu (perintah
 *    chroot blocking), lalu klien connect dengan retry terbatas
 *    (modul yang baru dimuat sesaat kemudian tetap ketemu); bila
 *    modul sudah dimuat pihak lain, klien langsung terhubung.
 *
 * Port 4714 (cek: tak ada pemakai 4714 di codebase; 4713 = default
 * native-protocol PulseAudio, 18081/18082 = Frida/Kismet, 18923 =
 * bluez, 27042 = frida-server). Localhost-only (127.0.0.1).
 *
 * Lifecycle (pola XSessionManager): singleton proses (application
 * context), BUKAN Service — receiver hidup selama proses Astro hidup,
 * jadi menu Desktop boleh ditutup tanpa mematikan suara; matinya
 * eksplisit lewat toggle (atau saat proses mati). Nol proses saat OFF:
 * tidak ada thread, socket, AudioTrack, maupun modul chroot yang
 * tertinggal (modul di-unload saat stop/putus).
 *
 * Threading: start()/stop() aman dari UI thread (hanya melahirkan
 * thread latar). Worker memiliki socket + AudioTrack (dibuat dan
 * dilepas di worker itu sendiri). Listener dipanggil di main thread.
 */
public class DesktopAudioReceiver {

    /** Host TERKUNCI: loopback saja (tidak pernah 0.0.0.0). */
    public static final String HOST = "127.0.0.1";
    /** Port socket TCP modul simple-protocol chroot. */
    public static final int PORT = 4714;
    /** Format audio mentah: PCM 16-bit, stereo, 44100 Hz (cocok dengan
     *  auto_null.monitor; 176400 byte/detik). */
    public static final int SAMPLE_RATE = 44100;

    private static final String MODULE_NAME = "module-simple-protocol-tcp";
    private static final String SOURCE_NAME = "auto_null.monitor";
    private static final int CONNECT_TRIES = 10;
    private static final int CONNECT_RETRY_MS = 500;
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_CHUNK = 4096;
    /** Faktor buffer AudioTrack (pola BluetoothAudioManager): min x2,
     *  sekitar 40-100 ms — latensi rendah tanpa underrun terus-menerus. */
    private static final int BUFFER_FACTOR = 2;

    /** Pemberitahuan perubahan status (tanpa argumen — pemanggil baca
     *  ulang isRunning()/getDetail(), jadi tidak ada argumen basi). */
    public interface Listener {
        void onDesktopAudioStateChanged();
    }

    private static DesktopAudioReceiver instance;

    public static synchronized DesktopAudioReceiver get(Context ctx) {
        if (instance == null) instance = new DesktopAudioReceiver(ctx.getApplicationContext());
        return instance;
    }

    private final Core core;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean starting = false;
    private volatile boolean stopRequested = false;
    private volatile String detail = "Mati";
    private volatile Listener listener;

    private volatile Socket socket;
    private volatile Thread worker;

    private DesktopAudioReceiver(Context ctx) {
        this.core = new Core(ctx);
    }

    public boolean isRunning() {
        return running;
    }

    public String getDetail() {
        return detail;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void setDetail(String d) {
        detail = d;
        Listener l = listener;
        if (l != null) main.post(l::onDesktopAudioStateChanged);
    }

    /** Nyalakan suara desktop (idempotent; aman dari UI thread). */
    public void start() {
        if (running || starting || (worker != null && worker.isAlive())) return;
        starting = true;
        stopRequested = false;
        setDetail("Menghubungkan…");
        Thread w = new Thread(this::runReceiver, "DesktopAudio");
        worker = w;
        w.start();
    }

    /** Matikan suara desktop (idempotent; aman dari UI thread). Menyetel
     *  flag + menutup socket (membuka blokir read). Worker yang sedang
     *  jalan membersihkan AudioTrack + unload modul chroot-nya sendiri
     *  (sinkron, sebelum thread selesai) — jadi start() berikutnya
     *  tidak balapan dengan unload yang masih jalan. */
    public void stop() {
        stopRequested = true;
        closeSocket();
        if (!running && !starting) setDetail("Mati");
    }

    private void runReceiver() {
        // 1) Muat modul simple-protocol di PA chroot (server socket PCM).
        if (!loadModule()) {
            starting = false;
            setDetail("Gagal: modul PulseAudio tidak bisa dimuat");
            core.logger.writeLine("DesktopAudio: gagal memuat " + MODULE_NAME, 3);
            return;
        }
        // 2) Hubungkan ke socket modul (retry terbatas — modul mungkin
        //    baru selesai dimuat; stop() membatalkan lewat flag).
        Socket s = connectWithRetry();
        if (s == null) {
            unloadModule();
            starting = false;
            if (!stopRequested) {
                setDetail("Gagal: tidak bisa terhubung ke 127.0.0.1:" + PORT);
                core.logger.writeLine("DesktopAudio: connect ke 127.0.0.1:" + PORT + " gagal", 3);
            } else {
                setDetail("Mati");
            }
            return;
        }
        socket = s;
        // 3) AudioTrack (pola BluetoothAudioManager: stereo 44100).
        AudioTrack track = createTrack();
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
            if (track != null) {
                try {
                    track.release();
                } catch (Exception ignored) {
                }
            }
            closeSocket();
            unloadModule();
            starting = false;
            setDetail("Gagal: AudioTrack tidak bisa dibuat");
            core.logger.writeLine("DesktopAudio: AudioTrack gagal dibuat", 3);
            return;
        }
        try {
            track.play();
        } catch (Exception e) {
            try {
                track.release();
            } catch (Exception ignored) {
            }
            closeSocket();
            unloadModule();
            starting = false;
            setDetail("Gagal: AudioTrack tidak bisa diputar");
            core.logger.writeLine("DesktopAudio: AudioTrack play gagal: " + e.getMessage(), 3);
            return;
        }
        // 4) Loop baca socket -> tulis AudioTrack.
        running = true;
        starting = false;
        setDetail("Menyala — 127.0.0.1:" + PORT);
        core.logger.writeLine("DesktopAudio: menyala (127.0.0.1:" + PORT + ")", 1);
        boolean cleanStop = false;
        try {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[READ_CHUNK];
            while (!stopRequested) {
                int n = in.read(buf, 0, buf.length);
                if (n <= 0) break; // EOF: sender menutup
                if (track.getState() == AudioTrack.STATE_INITIALIZED
                        && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    int w = track.write(buf, 0, n);
                    if (w < 0) break; // track mati
                }
            }
            cleanStop = stopRequested;
        } catch (IOException e) {
            // Socket ditutup stop() atau sender putus — bukan crash.
            cleanStop = stopRequested;
        } catch (Exception e) {
            core.logger.writeLine("DesktopAudio loop: " + e.getMessage(), 3);
            cleanStop = false;
        } finally {
            // 5) Bersihkan: socket + AudioTrack + modul chroot.
            closeSocket();
            try {
                if (track.getState() == AudioTrack.STATE_INITIALIZED
                        && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
            unloadModule();
            running = false;
            starting = false;
            setDetail(cleanStop ? "Mati" : "Terputus — sender berhenti");
            if (!cleanStop) {
                core.logger.writeLine("DesktopAudio: terputus dari sender", 3);
            }
        }
    }

    private AudioTrack createTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(min * BUFFER_FACTOR)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            core.logger.writeLine("DesktopAudio: AudioTrack gagal dibuat: " + e.getMessage(), 3);
            return null;
        }
    }

    private Socket connectWithRetry() {
        for (int i = 0; i < CONNECT_TRIES && !stopRequested; i++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                if (i + 1 < CONNECT_TRIES && !stopRequested) {
                    try {
                        Thread.sleep(CONNECT_RETRY_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Muat modul simple-protocol (idempotent: buang instance lama dulu).
     *  Mengembalikan true bila pactl mencetak indeks modul (angka). */
    private boolean loadModule() {
        ArrayList<String> out = core.customChrootCommand(loadModuleCommand());
        if (out == null) return false;
        for (String line : out) {
            if (line != null && line.trim().matches("[0-9]+")) return true;
        }
        return false;
    }

    private String loadModuleCommand() {
        return "M=$(pactl list modules short 2>/dev/null | awk '/" + MODULE_NAME
                + "/ {print $1; exit}'); "
                + "[ -n \"$M\" ] && pactl unload-module \"$M\" 2>/dev/null; "
                + "pactl load-module " + MODULE_NAME + " port=" + PORT
                + " record=true source=" + SOURCE_NAME
                + " rate=" + SAMPLE_RATE + " format=s16le channels=2";
    }

    private String unloadModuleCommand() {
        return "M=$(pactl list modules short 2>/dev/null | awk '/" + MODULE_NAME
                + "/ {print $1; exit}'); "
                + "[ -n \"$M\" ] && pactl unload-module \"$M\" 2>/dev/null; true";
    }

    /** Buang modul simple-protocol (idempotent). SYNCHRONOUS — hanya
     *  dipanggil dari worker (bukan UI thread). Dipanggil di semua
     *  jalur keluar worker setelah modul berhasil dimuat, SEBELUM
     *  starting=false, supaya start() berikutnya tidak bisa balapan
     *  dengan unload yang masih jalan. */
    private void unloadModule() {
        try {
            core.customChrootCommand(unloadModuleCommand(), true);
        } catch (Exception ignored) {
        }
    }
}
