package id.hawx.astro.engine;

import android.content.Context;

import java.io.File;

public final class RootlessPaths {

    private RootlessPaths() {}

    public static File base(Context c) {
        return new File(c.getFilesDir(), "rootless");
    }

    private static File nativeDir(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir);
    }

    public static File qemuBin(Context c)   { return new File(nativeDir(c), "libqemu.so"); }

    public static File libslirp(Context c)  { return new File(nativeDir(c), "libslirp.so"); }
    public static File kernel(Context c)    { return new File(base(c), "Image"); }
    public static File initrd(Context c)    { return new File(base(c), "initrd.img"); }
    public static File rootfs(Context c)    { return new File(base(c), "rootfs.img"); }
    public static File rootfsGz(Context c)  { return new File(base(c), "rootfs.img.gz"); }

    public static File qmpSock(Context c)   { return new File(base(c), "qmp.sock"); }
    public static File serialSock(Context c){ return new File(base(c), "serial.sock"); }
    public static File serialLog(Context c){ return new File(base(c), "serial.log"); }
    public static File termSock(Context c)  { return new File(base(c), "term.sock"); }
    public static File bootLog(Context c)   { return new File(base(c), "boot.log"); }


    public static File activeFlag(Context c) {
        return new File(base(c), ".active");
    }
    public static final String ACTIVE_FLAG_PATH =
            "/data/data/id.hawx.astro/files/rootless/.active";
}
