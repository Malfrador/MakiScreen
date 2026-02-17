package de.erethon.mccinema.download;

public enum DownloadState {
    FETCHING_INFO,
    SELECTING_FORMAT,
    DOWNLOADING,
    COMPLETED,
    ERROR,
    CANCELLED
}

