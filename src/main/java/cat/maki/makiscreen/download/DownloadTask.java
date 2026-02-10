package cat.maki.makiscreen.download;

import cat.maki.makiscreen.download.YoutubeDownloadManager.VideoInfo;

import java.io.File;
import java.util.UUID;

public class DownloadTask {

    private final UUID id;
    private final String videoIdOrUrl;
    private final String customName;
    private final DownloadProgressCallback callback;

    private DownloadState state;
    private VideoInfo videoInfo;
    private int progress;
    private File downloadedFile;
    private Throwable error;

    public DownloadTask(UUID id, String videoIdOrUrl, String customName, DownloadProgressCallback callback) {
        this.id = id;
        this.videoIdOrUrl = videoIdOrUrl;
        this.customName = customName;
        this.callback = callback;
        this.state = DownloadState.FETCHING_INFO;
        this.progress = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getVideoIdOrUrl() {
        return videoIdOrUrl;
    }

    public String getCustomName() {
        return customName;
    }

    public DownloadState getState() {
        return state;
    }

    public void setState(DownloadState state) {
        this.state = state;
    }

    public VideoInfo getVideoInfo() {
        return videoInfo;
    }

    public void setVideoInfo(VideoInfo videoInfo) {
        this.videoInfo = videoInfo;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public File getDownloadedFile() {
        return downloadedFile;
    }

    public void setDownloadedFile(File downloadedFile) {
        this.downloadedFile = downloadedFile;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }
}

