package id.hawx.astro.cameradar.install;

import id.hawx.astro.R;

public enum CameradarInstallStage {
    REFRESH(R.string.cameradar_install_stage_refresh),
    DOWNLOAD(R.string.cameradar_install_stage_download),
    UNPACK(R.string.cameradar_install_stage_unpack),
    VERIFY(R.string.cameradar_install_stage_verify);

    public final int titleRes;

    CameradarInstallStage(int titleRes) {
        this.titleRes = titleRes;
    }
}
