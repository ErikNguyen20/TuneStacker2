package com.example.tunestacker2.Data;


public class PlaylistVideoInfo {
    private String title;
    private String url;
    private String uploader;

    // Constructor
    public PlaylistVideoInfo(String title, String url, String uploader) {
        this.title = title;
        this.url = url;
        this.uploader = uploader;
    }

    // Getters
    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getUploader() {
        return uploader;
    }

    // Setters
    public void setTitle(String title) {
        this.title = title;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setUploader(String uploader) {
        this.uploader = uploader;
    }
}