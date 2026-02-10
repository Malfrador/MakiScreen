package cat.maki.makiscreen.download;

import java.io.File;

public interface DownloadProgressCallback {

    /**
     * Called periodically during download with progress percentage
     * @param progress Progress from 0 to 100
     */
    void onProgress(int progress);

    /**
     * Called when download completes successfully
     * @param file The downloaded file
     */
    void onComplete(File file);

    /**
     * Called when download fails
     * @param error The error that occurred
     */
    void onError(Throwable error);
}

