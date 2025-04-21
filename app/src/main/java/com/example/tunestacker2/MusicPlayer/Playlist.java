package com.example.tunestacker2.MusicPlayer;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Represents a playlist of songs with metadata such as title and last played timestamp.
 * Implements Parcelable to allow easy passing between Android components.
 */
public class Playlist implements Parcelable {
    private Uri jsonUri;      // URI pointing to the JSON resource representing this playlist
    private List<Song> songs; // List of songs in the playlist
    private String title;     // Title of the playlist
    private long lastPlayed;  // Timestamp of when the playlist was last played


    /**
     * Standard constructor for creating a Playlist instance.
     *
     * @param jsonUri    URI to the JSON representation of the playlist.
     * @param songs      List of Song objects.
     * @param title      Title of the playlist.
     * @param lastPlayed Last played timestamp.
     */
    public Playlist(Uri jsonUri, List<Song> songs, String title, long lastPlayed) {
        this.jsonUri = jsonUri;
        this.title = title;
        this.lastPlayed = lastPlayed;

        if (songs == null) {
            this.songs = new ArrayList<>();
        } else {
            this.songs = songs;
        }
    }

    /**
     * Constructor used by Parcelable to recreate object from Parcel.
     */
    protected Playlist(Parcel in) {
        jsonUri = in.readParcelable(Uri.class.getClassLoader());
        songs = in.createTypedArrayList(Song.CREATOR);
        title = in.readString();
        lastPlayed = in.readLong();
    }

    // --- Getters for class properties ---

    public Uri getJsonUri() {
        return jsonUri;
    }

    public List<Song> getSongs() {
        return songs;
    }

    public String getTitle() {
        return title;
    }

    public long getLastPlayed() {
        return lastPlayed;
    }

    /**
     * Replaces the current list of songs with a new list.
     *
     * @param newSongs List of songs to replace the current playlist.
     */
    public void setSongs(List<Song> newSongs) {
        this.songs.clear();
        this.songs.addAll(newSongs);
    }

    /**
     * Adds a list of songs to the playlist, avoiding duplicates.
     *
     * @param newSongs Songs to be added to the playlist.
     */
    public void addSongs(List<Song> newSongs) {
        Set<Song> existingSongs = new HashSet<>(this.songs);
        for (Song song : newSongs) {
            if (!existingSongs.contains(song)) {
                this.songs.add(song);
                existingSongs.add(song);
            }
        }
    }

    /**
     * Removes a song from the playlist.
     *
     * @param song Song to be removed.
     */
    public void removeSong(Song song) {
        songs.remove(song);
    }

    // --- Setters for mutable fields ---

    public void setTitle(String title) {
        this.title = title;
    }

    public void setLastPlayed(long lastPlayed) {
        this.lastPlayed = lastPlayed;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Playlist playlist = (Playlist) obj;
        return title.equals(playlist.title);
    }

    // --- Comparators ---
    public static final Comparator<Playlist> LATEST_COMPARATOR = (p1, p2) ->
            Long.compare(p2.getLastPlayed(), p1.getLastPlayed());

    // --- Parcelable boilerplate ---

    public static final Creator<Playlist> CREATOR = new Creator<Playlist>() {
        @Override
        public Playlist createFromParcel(Parcel in) {
            return new Playlist(in);
        }

        @Override
        public Playlist[] newArray(int size) {
            return new Playlist[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(jsonUri, flags);
        dest.writeTypedList(songs);
        dest.writeString(title);
        dest.writeLong(lastPlayed);
    }

}