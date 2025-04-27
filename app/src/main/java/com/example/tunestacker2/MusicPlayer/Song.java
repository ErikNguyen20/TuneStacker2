package com.example.tunestacker2.MusicPlayer;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;


/**
 * Represents a Song object with metadata including URI, title, and last modified timestamp.
 * Implements Parcelable for Android inter-process communication or intent passing.
 */
public class Song implements Parcelable {
    private final Uri audioUri;         // URI pointing to the song's audio file
    private final String title;         // Human-readable title of the song (derived from file name)
    private final long lastModified;    // Last modified timestamp of the audio file


    /**
     * Constructor using filename and URI. Assumes lastModified is unknown (set to 0).
     * @param fileName The file name of the audio file.
     * @param audioUri The URI pointing to the file.
     */
    public Song(String fileName, Uri audioUri) {
        this(fileName, audioUri, 0);
    }

    /**
     * Full constructor with all fields.
     * @param fileName The display name of the audio.
     * @param audioUri The URI pointing to the file.
     * @param lastModified Timestamp indicating when the file was last modified.
     */
    public Song(String fileName, Uri audioUri, long lastModified) {
        this.audioUri = audioUri;
        this.title = fileName;
        this.lastModified = lastModified;
    }

    /**
     * Constructor used by Parcelable to recreate object from a Parcel.
     * @param in Parcel containing serialized Song data.
     */
    protected Song(Parcel in) {
        audioUri = in.readParcelable(Uri.class.getClassLoader());
        title = in.readString();
        lastModified = in.readLong();
    }

    // --- Getters ---

    public Uri getAudioUri() {
        return audioUri;
    }

    public String getTitle() {
        return title;
    }

    public long getLastModified() {
        return lastModified;
    }

    // --- Equality based on audio URI ---

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Song song = (Song) obj;
        return audioUri.equals(song.audioUri);
    }

    @Override
    public int hashCode() {
        return audioUri != null ? audioUri.hashCode() : 0;
    }

    // --- Comparators ---

    public static final Comparator<Song> ALPHABETICAL_COMPARATOR = (s1, s2) -> {
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        return collator.compare(s1.getTitle(), s2.getTitle());
    };

    public static final Comparator<Song> NEWEST_COMPARATOR = (s1, s2) ->
            Long.compare(s2.getLastModified(), s1.getLastModified());

    public static final Comparator<Song> OLDEST_COMPARATOR = (s1, s2) ->
            Long.compare(s1.getLastModified(), s2.getLastModified());

    // --- Parcelable boilerplate ---

    public static final Creator<Song> CREATOR = new Creator<Song>() {
        @Override
        public Song createFromParcel(Parcel in) {
            return new Song(in);
        }

        @Override
        public Song[] newArray(int size) {
            return new Song[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(audioUri, flags);
        dest.writeString(title);
        dest.writeLong(lastModified);
    }


}
