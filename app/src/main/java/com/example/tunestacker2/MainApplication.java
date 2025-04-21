package com.example.tunestacker2;

import android.app.Application;

import com.example.tunestacker2.Data.DataManager;

public class MainApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize DataManager with the application context
        DataManager.initialize(getApplicationContext());
        DataManager.Settings.LoadSettings();
    }
}

