package com.example.tunestacker2.Data;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class YoutubeDLCallbackAdapter implements Function3<Float, Long, String, Unit> {
    private final DownloaderCallback downloaderCallback;

    public YoutubeDLCallbackAdapter(DownloaderCallback downloaderCallback) {
        this.downloaderCallback = downloaderCallback;
    }

    @Override
    public Unit invoke(Float progress, Long etaInSeconds, String line) {
        downloaderCallback.onProgressUpdate(progress, etaInSeconds, line);
        return Unit.INSTANCE;
    }
}
