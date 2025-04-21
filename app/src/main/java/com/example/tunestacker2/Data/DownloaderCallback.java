package com.example.tunestacker2.Data;

public interface DownloaderCallback {
    public void onProgressUpdate(float progress, long etaInSeconds, String line);
}
