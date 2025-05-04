package com.example.tunestacker2.Data;

public interface YtDLPDownloaderCallback {
    public void onProgressUpdate(float progress, long etaInSeconds, String line);
}
