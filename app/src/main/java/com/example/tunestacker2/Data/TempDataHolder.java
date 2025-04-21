package com.example.tunestacker2.Data;

import com.example.tunestacker2.MusicPlayer.Song;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TempDataHolder {
    private static final Map<String, List<Song>> dataMap = new HashMap<>();

    public static void put(String key, List<Song> songs) {
        dataMap.put(key, songs);
    }

    public static List<Song> get(String key) {
        return dataMap.get(key);
    }

    public static void remove(String key) {
        dataMap.remove(key);
    }
}

