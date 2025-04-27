package com.example.tunestacker2.Data;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.tunestacker2.MusicPlayer.Playlist;
import com.example.tunestacker2.MusicPlayer.Song;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;

import java.util.List;
import java.util.function.Consumer;


/**
 * Manages playlist and song data for TuneStacker.
 * Handles persistent storage, background operations, and shared preferences.
 */
public class DataManager {

    // --- Constants ---
    private static final String LOG = "DataManager";
    private static final String SHARED_PREFERENCE_NAME = "tunestacker2_prefs1";
    private static final String PREF_KEY_LAST_UPDATED = "last_updated";

    // --- Singleton Instance ---
    private static DataManager instance = null;

    // --- Data Members ---
    private List<Playlist> playlists = new ArrayList<>();
    private Context context;

    // --- Background Operations ---
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object playlistLock = new Object();


    /**
     * Initializes the DataManager instance and required third-party libraries.
     *
     * @param context Application context.
     */
    private DataManager(Context context) {
        this.context = context.getApplicationContext();

        try {
            YoutubeDL.getInstance().init(this.context);
            FFmpeg.getInstance().init(this.context);
        } catch (YoutubeDLException e) {
            Log.e(LOG, "Error initializing third-party libraries", e);
            e.printStackTrace();
        }
    }

    /**
     * Initializes the singleton instance of DataManager.
     *
     * @param context Application context.
     */
    public static synchronized void initialize(Context context) {
        if(instance == null) {
            instance = new DataManager(context);
        }
    }

    /**
     * Gets the singleton instance of DataManager.
     *
     * @return DataManager instance.
     * @throws IllegalStateException if not initialized.
     */
    public static DataManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DataManager is not initialized.");
        }
        return instance;
    }

    /**
     * Retrieves the last update timestamp.
     *
     * @return Last update time in milliseconds.
     */
    public long getLastUpdated() {
        return context.getSharedPreferences(SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_KEY_LAST_UPDATED, 0);
    }

    /**
     * Stores the current time as the last updated timestamp.
     *
     * @param time Time in milliseconds.
     */
    public void setLastUpdated(long time) {
        context.getSharedPreferences(SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_KEY_LAST_UPDATED, time)
                .apply();
    }

    /**
     * Asynchronously loads playlists from the audio directory and returns via callback.
     *
     * @param callback Consumer to receive the playlist list on the main thread.
     */
    public void getPlaylistsAsync(Consumer<List<Playlist>> callback) {
        if (Settings.GetAudioDirectory() == null) return;

        executor.execute(() -> {
            playlists = FileUtils.batchedPlaylistsFromJsonFiles(context, Settings.GetAudioDirectory());

            // Post result to UI thread
            if(callback != null) handler.post(() -> callback.accept(playlists));
        });
    }

    /**
     * Gets a list of all existing playlist names.
     *
     * @return List of playlist titles.
     */
    public List<String> getAllPlaylistNames() {
        synchronized (playlistLock) {
            if (playlists == null) return new ArrayList<>();

            List<String> names = new ArrayList<>();
            for (Playlist playlist : playlists) {
                names.add(playlist.getTitle());
            }
            return names;
        }
    }

    /**
     * Asynchronously creates a new playlist if one with the same name doesn't already exist.
     *
     * @param rawTitle Playlist name.
     * @param callback Callback with null on success or error message on failure.
     */
    public void newPlaylistAsync(String rawTitle, Consumer<String> callback) {
        if (Settings.GetAudioDirectory() == null || rawTitle == null) return;

        String playlistName = FileUtils.sanitizeFilename(rawTitle);
        Uri audioDir = Settings.GetAudioDirectory();

        executor.execute(() -> {
            List<Playlist> existingPlaylists = FileUtils.batchedPlaylistsFromJsonFiles(context, audioDir);

            // Check for duplicates display names
            for (Playlist playlist : existingPlaylists) {
                if (playlist.getTitle().equals(playlistName)) {
                    if(callback != null) handler.post(() -> callback.accept("Playlist name already exists."));
                    return;
                }
            }

            // Check for duplicate file names
            for (Playlist playlist : existingPlaylists) {
                if (FileUtils.getFileNameWithoutExtensionFromUri(context, playlist.getJsonUri()).equals(playlistName)) {
                    if(callback != null) handler.post(() -> callback.accept("Playlist File of this name already exists."));
                    return;
                }
            }

            // Create new playlist
            Playlist newPlaylist = new Playlist(null, null, playlistName, new Date().getTime());
            boolean result = FileUtils.writePlaylistToJsonFile(context, newPlaylist, audioDir);

            if (result) {
                playlists.add(newPlaylist);
                if(callback != null) handler.post(() -> callback.accept(null));
            } else {
                if(callback != null) handler.post(() -> callback.accept("Failed to write json file."));
            }
        });
    }

    /**
     * Asynchronously updates the "last played" timestamp of a playlist.
     *
     * @param playlistName Playlist name.
     * @param callback     Callback with true on success, false on failure.
     */
    public void updateTimePlaylistAsync(String playlistName, Consumer<Boolean> callback) {
        if (Settings.GetAudioDirectory() == null || playlists == null || playlistName == null) return;

        executor.execute(() -> {
            // Modify and save matching playlist in background
            for (int i = 0; i < playlists.size(); i++) {
                if (playlistName.equals(playlists.get(i).getTitle())) {
                    playlists.get(i).setLastPlayed(new Date().getTime());

                    boolean result = FileUtils.writePlaylistToJsonFile(context.getApplicationContext(), playlists.get(i), Settings.GetAudioDirectory());
                    if(!result) {
                        if(callback != null) handler.post(() -> callback.accept(false));
                    }
                    break;
                }
            }

            // Refresh playlists back on UI thread
            if(callback != null) handler.post(() -> callback.accept(true));
        });
    }

    /**
     * Asynchronously renames a playlist, ensuring the new name doesn't already exist.
     *
     * @param playlistName Current playlist name.
     * @param newName      New name to assign.
     * @param callback     Callback with null on success or error message on failure.
     */
    public void updateNamePlaylistAsync(String playlistName, String newName, Consumer<String> callback) {
        if (Settings.GetAudioDirectory() == null || playlists == null || playlistName == null || newName == null) return;

        String cleanName = FileUtils.sanitizeFilename(newName);
        executor.execute(() -> {
            List<Playlist> existingPlaylists = FileUtils.batchedPlaylistsFromJsonFiles(context, Settings.GetAudioDirectory());

            // Check for duplicates
            for (Playlist playlist : existingPlaylists) {
                if (playlist.getTitle().equals(newName)) {
                    if(callback != null) handler.post(() -> callback.accept("Playlist name already in use."));
                    return;
                }
            }

            // Modify and save matching playlist in background
            for (int i = 0; i < playlists.size(); i++) {
                if (playlistName.equals(playlists.get(i).getTitle())) {
                    playlists.get(i).setTitle(cleanName);

                    boolean result = FileUtils.writePlaylistToJsonFile(context.getApplicationContext(), playlists.get(i), Settings.GetAudioDirectory());
                    if (result) {
                        if(callback != null) handler.post(() -> callback.accept(null));
                        return;
                    }
                    break;
                }
            }

            // Refresh playlists back on UI thread
            if(callback != null) handler.post(() -> callback.accept("Failed to rename playlist."));
        });
    }

    /**
     * Asynchronously deletes a playlist and removes it from memory.
     *
     * @param playlistName Playlist to delete.
     * @param callback     Callback with true on success, false on failure.
     */
    public void removePlaylistAsync(String playlistName, Consumer<Boolean> callback) {
        if (Settings.GetAudioDirectory() == null || playlists == null || playlistName == null) return;

        executor.execute(() -> {
            // Remove the playlist
            for (int i = 0; i < playlists.size(); i++) {
                if (playlistName.equals(playlists.get(i).getTitle())) {
                    boolean result = FileUtils.deleteFileUri(context.getApplicationContext(), playlists.get(i).getJsonUri());
                    if(!result) {
                        if(callback != null) handler.post(() -> callback.accept(false));
                    }
                    playlists.remove(i);
                    break;
                }
            }
            // Refresh playlists back on UI thread
            if(callback != null) handler.post(() -> callback.accept(true));
        });
    }

    /**
     * Asynchronously adds songs to selected playlists and saves them.
     *
     * @param songs         Songs to be added.
     * @param playlistNames Names of target playlists.
     * @param callback      Callback with true on success, false on failure.
     */
    public void addSongsToPlaylistsAsync(List<Song> songs, List<String> playlistNames, Consumer<Boolean> callback) {
        if (playlistNames == null || playlistNames.isEmpty() || songs == null || songs.isEmpty()) return;
        if (Settings.GetAudioDirectory() == null || playlists == null) return;

        executor.execute(() -> {
            Set<String> nameSet = new HashSet<>(playlistNames);

            // Modify and save matching playlists in background
            for (int i = 0; i < playlists.size(); i++) {
                if (nameSet.contains(playlists.get(i).getTitle())) {
                    playlists.get(i).addSongs(songs);

                    boolean result = FileUtils.writePlaylistToJsonFile(context.getApplicationContext(), playlists.get(i), Settings.GetAudioDirectory());
                    if(!result) {
                        if(callback != null) handler.post(() -> callback.accept(false));
                        return;
                    }
                }
            }

            // Refresh playlists back on UI thread
            if(callback != null) handler.post(() -> callback.accept(true));
        });
    }

    /**
     * Asynchronously replaces all songs in a playlist.
     *
     * @param playlistName Target playlist.
     * @param songs        New list of songs.
     * @param callback     Callback with true on success, false on failure.
     */
    public void updateSongsInPlaylistAsync(String playlistName, List<Song> songs, Consumer<Boolean> callback) {
        if (Settings.GetAudioDirectory() == null || playlists == null || playlistName == null || songs == null) return;
        List<Song> safeCopy = new ArrayList<>(songs);


        Log.d("DataManager", "Attempting to update songs in playlist: " + playlistName + " with " + safeCopy.size() + " songs.");
        executor.execute(() -> {
            // Modify and save matching playlist in background
            for (int i = 0; i < playlists.size(); i++) {
                if (playlistName.equals(playlists.get(i).getTitle())) {
                    playlists.get(i).setSongs(safeCopy);
                    Log.d("DataManager", "Updated songs in playlist: " + playlistName + " with " + safeCopy.size() + " songs.");

                    boolean result = FileUtils.writePlaylistToJsonFile(context.getApplicationContext(), playlists.get(i), Settings.GetAudioDirectory());
                    if(!result) {
                        if(callback != null) handler.post(() -> callback.accept(false));
                    }
                    break;
                }
            }

            // Refresh playlists back on UI thread
            if(callback != null) handler.post(() -> callback.accept(true));
        });
    }

    /**
     * Asynchronously removes a song from a specified playlist.
     *
     * @param playlistName Name of the playlist.
     * @param song         Song to remove.
     * @param callback     Callback with true on success, false on failure.
     */
    public void removeSongInPlaylistAsync(String playlistName, Song song, Consumer<Boolean> callback) {
        if (Settings.GetAudioDirectory() == null || playlists == null || playlistName == null) return;

        executor.execute(() -> {
            // Remove the song
            for (int i = 0; i < playlists.size(); i++) {
                if (playlistName.equals(playlists.get(i).getTitle())) {
                    playlists.get(i).removeSong(song);

                    boolean result = FileUtils.writePlaylistToJsonFile(context.getApplicationContext(), playlists.get(i), Settings.GetAudioDirectory());
                    if(!result) {
                        if(callback != null) handler.post(() -> callback.accept(false));
                    }
                    break;
                }
            }
            // Refresh playlists back on UI thread
            if(callback != null) handler.post(() -> callback.accept(true));
        });
    }

    /**
     * Asynchronously retrieves all audio files in the library directory.
     *
     * @param callback Callback with the list of songs.
     */
    public void getSongsInDirectory(Consumer<List<Song>> callback) {
        executor.execute(() -> {
            List<Song> updatedSongs = FileUtils.listAudioFilesFromDirectory(context.getApplicationContext(), Settings.GetAudioDirectory());
            if(callback != null) handler.post(() -> callback.accept(updatedSongs));
        });
    }


    /**
     * Static class that manages persistent application settings.
     */
    public static class Settings {
        // --- Preference Keys ---
        private static final String PREF_KEY_LIBRARY_URI = "library_uri";
        private static final String PREF_KEY_FILE_EXTENSION = "file_extension";
        private static final String PREF_KEY_EMBED_THUMBNAIL = "embed_thumbnail";
        private static final String PREF_KEY_EMBED_METADATA = "embed_metadata";
        private static final String PREF_KEY_AUTO_UPDATE = "auto_update";
        private static final String PREF_KEY_SORT_ORDER = "sort_order";

        // --- Settings Values ---
        private static Uri libraryUri;
        private static String fileExtension;
        private static boolean embedThumbnail;
        private static boolean embedMetadata;
        private static boolean autoUpdate;
        private static int sortOrder;


        /**
         * Loads all persisted settings from SharedPreferences.
         */
        public static void LoadSettings() {
            LoadAudioDirectory();
            LoadFileExtension();
            LoadEmbedThumbnail();
            LoadAutoUpdate();
            LoadSortOrder();
        }

        // --- Settings Getters and Setters ---

        public static void SetAudioDirectory(Uri directoryUri) {
            Context ctx = DataManager.getInstance().context;
            ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Settings.PREF_KEY_LIBRARY_URI, directoryUri.toString())
                    .apply();
            libraryUri = directoryUri;
        }

        private static void LoadAudioDirectory() {
            Context ctx = DataManager.getInstance().context;
            String uriString = ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .getString(Settings.PREF_KEY_LIBRARY_URI, null);
            if (uriString != null) {
                Uri uri = Uri.parse(uriString);
                libraryUri = uri;
            } else {
                libraryUri = null;
            }
        }

        public static Uri GetAudioDirectory() {
            return libraryUri;
        }

        public static void SetFileExtension(String extension) {
            Context ctx = DataManager.getInstance().context;
            ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Settings.PREF_KEY_FILE_EXTENSION, extension)
                    .apply();
            fileExtension = extension;
        }

        private static void LoadFileExtension() {
            Context ctx = DataManager.getInstance().context;
            String extension = ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .getString(Settings.PREF_KEY_FILE_EXTENSION, null);
            if (extension != null) {
                fileExtension = extension;
            } else {
                // Default to opus
                fileExtension = "opus";
            }
        }

        public static String GetFileExtension() {
            return fileExtension;
        }

        public static void SetEmbedThumbnail(boolean embed) {
            Context ctx = DataManager.getInstance().context;
            ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Settings.PREF_KEY_EMBED_THUMBNAIL, embed)
                    .apply();
            embedThumbnail = embed;
        }

        private static void LoadEmbedThumbnail() {
            Context ctx = DataManager.getInstance().context;
            boolean embed = ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .getBoolean(Settings.PREF_KEY_EMBED_THUMBNAIL, false);
            embedThumbnail = embed;
        }

        public static boolean GetEmbedThumbnail() {
            return embedThumbnail;
        }

        public static void SetAutoUpdate(boolean update) {
            Context ctx = DataManager.getInstance().context;
            ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Settings.PREF_KEY_AUTO_UPDATE, update)
                    .apply();
            autoUpdate = update;
        }

        private static void LoadAutoUpdate() {
            Context ctx = DataManager.getInstance().context;
            boolean update = ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .getBoolean(Settings.PREF_KEY_AUTO_UPDATE, false);
            autoUpdate = update;
        }

        public static boolean GetAutoUpdate() {
            return autoUpdate;
        }

        public static void SetSortOrder(int order) {
            Context ctx = DataManager.getInstance().context;
            ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(Settings.PREF_KEY_SORT_ORDER, order)
                    .apply();
            sortOrder = order;
        }

        private static void LoadSortOrder() {
            Context ctx = DataManager.getInstance().context;
            int order = ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .getInt(Settings.PREF_KEY_SORT_ORDER, 0);
            sortOrder = order;
        }

        public static int GetSortOrder() {
            return sortOrder;
        }

        public static void SetEmbedMetadata(boolean embed) {
            Context ctx = DataManager.getInstance().context;
            ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Settings.PREF_KEY_EMBED_METADATA, embed)
                    .apply();
            embedMetadata = embed;
        }

        private static void LoadEmbedMetadata() {
            Context ctx = DataManager.getInstance().context;
            boolean embed = ctx.getSharedPreferences(DataManager.SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE)
                    .getBoolean(Settings.PREF_KEY_EMBED_METADATA, false);
            embedMetadata = embed;
        }

        public static boolean GetEmbedMetadata() {
            return embedMetadata;
        }
    }
}
