package com.example.tunestacker2.MusicPlayer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.tunestacker2.MainActivity;
import com.example.tunestacker2.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * A background service for managing media playback using MediaPlayer.
 * Handles playlist management, audio focus, media session integration,
 * foreground service notification, and playback controls.
 */
public class MediaPlayerService extends Service {
    private static final String TAG = "MediaPlayerService";

    // --- Notification Constants ---
    private static final String CHANNEL_ID = "MediaPlaybackChannel";
    private static final int NOTIFICATION_ID = 1;

    // --- Intent Actions for Media Control ---
    public static final String ACTION_PLAY = "com.example.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.ACTION_PAUSE";
    public static final String ACTION_STOP = "com.example.ACTION_STOP";
    public static final String ACTION_NEXT = "com.example.ACTION_NEXT";
    public static final String ACTION_PREV = "com.example.ACTION_PREV";

    // --- Service State ---
    private final IBinder binder = new LocalBinder();
    public static boolean isServiceRunning = false;
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener afChangeListener;

    // Current playlist being played
    private final List<Song> songList = new ArrayList<>();
    // Index of the currently playing song in the songList
    private int currentlyPlayingPosition = -1; // Initialize to -1 to indicate no song loaded initially
    // Tracks songs that caused errors during preparation/playback to avoid retrying them repeatedly
    private final Set<String> problemSongTitles = new HashSet<>();
    private boolean isPrepared = false;
    private int repeatState = 2; // 0 = off, 1 = repeat one, 2 = repeat all
    private boolean isInitialized = false;
    private boolean resumeOnFocusGain = false;

    // --- Progress Update Handling ---
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            // Check if media player is valid and playing
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                updateNotification();
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                progressHandler.postDelayed(this, 500);
            }
        }
    };

    // --- MediaPlayer Listeners ---
    /**
     * Listener triggered when a media file finishes playing.
     * Automatically proceeds to the next song.
     */
    private final MediaPlayer.OnCompletionListener completionListener = mp -> {
        Log.d(TAG, "Playback completed for: " + (songList.isEmpty() ? "N/A" : songList.get(currentlyPlayingPosition).getTitle()));
        if(repeatState == 1) {
            skipTo(currentlyPlayingPosition); // Repeat same song
        }
        else {
            next(); // Move to the next song
        }
    };

    /**
     * Listener triggered when an error occurs during playback or setup.
     * Logs the error, marks the song as problematic, and skips to the next song.
     */
    private final MediaPlayer.OnErrorListener errorListener = (mp, what, extra) -> {
        Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra + " for song: " + (songList.isEmpty() ? "N/A" : songList.get(currentlyPlayingPosition).getTitle()));
        // Add the current song title to the set of problematic songs
        if (currentlyPlayingPosition >= 0 && currentlyPlayingPosition < songList.size()) {
            problemSongTitles.add(songList.get(currentlyPlayingPosition).getTitle());
        }
        isPrepared = false;
        next();
        return true;
    };

    /**
     * Listener triggered when the MediaPlayer has successfully prepared the media content.
     * Updates metadata, starts the foreground service, and begins playback.
     */
    private final MediaPlayer.OnPreparedListener preparedListener = mp -> {
        Log.d(TAG, "MediaPlayer prepared for: " + songList.get(currentlyPlayingPosition).getTitle());
        Song currentSong = songList.get(currentlyPlayingPosition);
        isPrepared = true;

        // Update MediaSession metadata with the current song's details
        updateMetadata(currentSong);
        problemSongTitles.remove(currentSong.getTitle());

        // Start the service in the foreground with the media notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildMediaStyleNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildMediaStyleNotification());
        }

        play();
    };

    // --- Binder for Service Communication ---

    /**
     * Provides a Binder interface for clients (like Activities) to get a reference
     * to this service instance and call its public methods.
     */
    public class LocalBinder extends Binder {
        public MediaPlayerService getService() {
            return MediaPlayerService.this;
        }
    }

    // --- Broadcast Receiver for Audio Becoming Noisy ---

    /**
     * BroadcastReceiver that listens for AudioManager.ACTION_AUDIO_BECOMING_NOISY.
     * This intent is broadcast when audio output is about to change (e.g., headphones unplugged).
     * Pauses playback to prevent audio from playing through the device speaker unexpectedly.
     */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check if the action is for audio becoming noisy
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.d(TAG, "Audio becoming noisy, pausing playback.");
                // If the media player is currently playing, pause it
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    pause();
                }
            }
        }
    };

    // --- Service Lifecycle Methods ---

    /**
     * Called by the system when the service is first created.
     * Initializes essential components like MediaSession, AudioManager,
     * notification channel, and registers broadcast receivers.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize the MediaSession for handling media controls
        initMediaSession();

        // Create the notification channel required for foreground service notifications on Android O+
        createNotificationChannel();

        // Setup AudioManager and the listener for audio focus changes
        initAudioManager();

        // Register the receiver for handling noisy audio events (e.g., headphone unplug)
        registerNoisyReceiver();

        isServiceRunning = true;
    }

    /**
     * Called by the system every time a client starts the service using startService().
     * Handles incoming Intents, usually containing actions to control playback.
     * @param intent The Intent supplied to startService(), may contain an action.
     * @param flags Additional data about the start request.
     * @param startId A unique integer representing this specific start request.
     * @return The behavior if the service is killed (START_STICKY tries to restart it).
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received action: " + (intent != null ? intent.getAction() : "null intent"));
        // Handle the action specified in the intent
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_PLAY:
                    play();
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_NEXT:
                    next();
                    break;
                case ACTION_PREV:
                    previous();
                    break;
                case ACTION_STOP:
                    // Stop playback and prepare to shut down if necessary
                    stopServiceAndReleaseResources();
                    break;
            }
        }
        // START_STICKY ensures that if the service is killed by the system,
        // it will be restarted, but the last intent will not be redelivered.
        // Suitable for a media player that should persist.
        return START_STICKY;
    }

    /**
     * Called by the system when a client binds to the service using bindService().
     * @param intent The Intent that was used to bind to this service.
     * @return The IBinder through which clients can call methods on the service.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called");
        return binder; // Return the communication channel to the service
    }

    /**
     * Called when the task that the service is associated with (usually the app's UI) is removed.
     * This is a good place to stop the service completely if playback should not continue
     * after the app is swiped away from the recent tasks list.
     * @param rootIntent The original intent that started the task.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved called");
        super.onTaskRemoved(rootIntent);
        // Stop playback and release resources, then stop the service itself
        stopServiceAndReleaseResources();
    }

    /**
     * Called by the system when the service is no longer used and is being destroyed.
     * Ensures all resources are released properly (MediaPlayer, MediaSession, receivers, etc.).
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        // Perform final cleanup
        releaseResources();
        isServiceRunning = false;
        super.onDestroy();
    }

    // --- Initialization Helper Methods ---

    /**
     * Initializes the MediaSessionCompat, sets its callbacks, and marks it as active.
     * The MediaSession is crucial for integrating with system media controls.
     */
    private void initMediaSession() {
        Log.d(TAG, "Initializing MediaSession");

        mediaSession = new MediaSessionCompat(this, TAG); // Use TAG for session logging
        mediaSession.setActive(true);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { Log.d(TAG, "MediaSession Callback: onPlay"); play(); }
            @Override public void onPause() { Log.d(TAG, "MediaSession Callback: onPause"); pause(); }
            @Override public void onSkipToNext() { Log.d(TAG, "MediaSession Callback: onSkipToNext"); next(); }
            @Override public void onSkipToPrevious() { Log.d(TAG, "MediaSession Callback: onSkipToPrevious"); previous(); }
            @Override public void onStop() { Log.d(TAG, "MediaSession Callback: onStop"); stopServiceAndReleaseResources(); } // Handle stop command
            @Override public void onSeekTo(long pos) { Log.d(TAG, "MediaSession Callback: onSeekTo"); SeekTo((int) pos); }
        });
        // Set initial playback state to stopped
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
    }

    /**
     * Initializes the AudioManager and sets up the listener for audio focus changes.
     * Audio focus handling is mandatory for well-behaved audio applications.
     */
    private void initAudioManager() {
        Log.d(TAG, "Initializing AudioManager");

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // Define the listener for audio focus changes
        afChangeListener = focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // Loss of focus: Stop playback and abandon focus
                    Log.d(TAG, "Audio Focus: AUDIOFOCUS_LOSS");
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        pause(); // Should we stop here? idk
                        resumeOnFocusGain = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Temporary loss (e.g., incoming call): Pause playback
                    Log.d(TAG, "Audio Focus: AUDIOFOCUS_LOSS_TRANSIENT");
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        pause();
                        resumeOnFocusGain = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Temporary loss, allowed to duck (lower volume) (e.g., navigation announcement)
                    Log.d(TAG, "Audio Focus: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.setVolume(0.2f, 0.2f);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    // Regained focus: Resume playback if needed, restore volume
                    Log.d(TAG, "Audio Focus: AUDIOFOCUS_GAIN");
                    if (resumeOnFocusGain) {
                        if (!isPrepared && currentlyPlayingPosition != -1) {
                            prepareAndPlayTrack(currentlyPlayingPosition);
                        } else if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                            play();
                        }
                        resumeOnFocusGain = false;
                    }
                    // Restore full volume
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                    }
                    break;
            }
        };
    }

    /**
     * Creates the notification channel required for displaying notifications on Android 8.0 (Oreo) and above.
     * This only needs to be done once.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Channel for media playback controls and notifications");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC); // Show on lock screen

            // Get the NotificationManager system service
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    /**
     * Registers the BroadcastReceiver for ACTION_AUDIO_BECOMING_NOISY.
     */
    private void registerNoisyReceiver() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(noisyReceiver, filter);
    }

    // --- Public Control Methods (Called via Binder or Intents) ---

    /**
     * Sets the playlist for the service and starts playback from a specified position.
     * Clears any previous playlist and problematic song tracking.
     *
     * @param playlist The list of Song objects to play.
     * @param startPosition The index in the playlist to start playback from.
     */
    public void setPlaylist(List<Song> playlist, int startPosition) {
        Log.d(TAG, "Setting playlist with size: " + playlist.size() + ", starting at position: " + startPosition);
        problemSongTitles.clear();
        songList.clear();
        songList.addAll(playlist);

        // Validate start position
        if (startPosition < 0 || startPosition >= songList.size()) {
            currentlyPlayingPosition = 0;
        } else {
            currentlyPlayingPosition = startPosition;
        }
        isInitialized = true;

        // If the playlist is not empty, prepare and play the selected song
        if (!songList.isEmpty()) {
            prepareAndPlayTrack(currentlyPlayingPosition);
        } else {
            Log.w(TAG, "Playlist is empty, not starting playback.");
            stopServiceAndReleaseResources();
        }
    }

    /**
     * Initiates playback if the MediaPlayer is prepared and not already playing.
     * Requests audio focus before starting.
     */
    public void play() {
        if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
            // Request audio focus
            int result = audioManager.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

            // Check if audio focus was granted
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                safePlay();
            } else {
                Log.w(TAG, "Audio focus not granted, cannot start playback.");
            }
        }
    }

    /**
     * Starts playback if the MediaPlayer is prepared and not already playing.
     */
    private void safePlay() {
        if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            mediaSession.setActive(true); // Ensure session is active
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);

            progressHandler.removeCallbacks(progressRunnable);
            progressHandler.post(progressRunnable);
            updateNotification();
        }
    }

    /**
     * Pauses playback if the MediaPlayer is currently playing.
     * Updates the playback state and notification accordingly.
     */
    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();

            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            audioManager.abandonAudioFocus(afChangeListener);

            progressHandler.removeCallbacks(progressRunnable);
            updateNotification();
        }
    }

    /**
     * Stops playback, releases audio focus, updates state, and potentially stops the foreground service.
     * This is intended for user-initiated stops or when playback naturally ends.
     */
    private void stopPlaybackAndCleanup() {
        Log.d(TAG, "stopPlaybackAndCleanup() called");

        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                try {
                    mediaPlayer.stop(); // Stop playback
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaPlayer stop called in invalid state.", e);
                    // May happen if already stopped or not initialized, often safe to ignore
                }
            }

            // Reset player state, ready for potential reuse with prepareAndPlayTrack
            mediaPlayer.reset();
            isPrepared = false;
        }

        if(audioManager != null && afChangeListener != null) audioManager.abandonAudioFocus(afChangeListener);

        // Stop progress updates
        progressHandler.removeCallbacks(progressRunnable);
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
        if(mediaSession != null) mediaSession.setMetadata(null);

        // Remove the notification, but allow the service to continue running if needed
        stopForeground(true); // True = remove notification
        Log.d(TAG, "Foreground service stopped, notification removed.");
    }


    /**
     * Completely stops the service and releases all associated resources.
     * Called from onTaskRemoved or when explicitly stopping the service.
     */
    private void stopServiceAndReleaseResources() {
        Log.d(TAG, "stopServiceAndReleaseResources() called");
        // Stop playback and perform basic cleanup first
        stopPlaybackAndCleanup();
        releaseResources();
        stopSelf();
        isServiceRunning = false;
    }

    /**
     * Skips to the specific track in the playlist specified by pos.
     * @param pos The index of the track to skip to.
     */
    public void skipTo(int pos) {
        if(pos < 0 || pos >= songList.size()) {
            Log.w(TAG, "SkipTo called, but playlist is empty.");
            stopServiceAndReleaseResources(); // Stop if nothing to play
            return;
        }

        currentlyPlayingPosition = pos;
        prepareAndPlayTrack(currentlyPlayingPosition);
    }

    /**
     * Skips to the next track in the playlist. Wraps around to the beginning if at the end.
     */
    public int next() {
        if (songList.isEmpty()) {
            Log.w(TAG, "Next called, but playlist is empty.");
            stopServiceAndReleaseResources(); // Stop if nothing to play
            return -1;
        }

        if(repeatState == 0 && currentlyPlayingPosition == songList.size() - 1) {
            // End Service when all songs have been played
            stopServiceAndReleaseResources();
            return -1;
        }
        int nextPosition = (currentlyPlayingPosition + 1) % songList.size();
        prepareAndPlayTrack(nextPosition);
        return nextPosition;
    }

    /**
     * Skips to the previous track in the playlist. Wraps around to the end if at the beginning.
     */
    public int previous() {
        if (songList.isEmpty()) {
            Log.w(TAG, "Previous called, but playlist is empty.");
            stopServiceAndReleaseResources(); // Stop if nothing to play
            return -1;
        }

        int prevPosition = (currentlyPlayingPosition - 1 + songList.size()) % songList.size();
        prepareAndPlayTrack(prevPosition);
        return prevPosition;
    }

    /**
     * Seeks to a specific position within the currently playing track.
     * @param positionMillis The position to seek to, in milliseconds.
     */
    public void SeekTo(int positionMillis) {
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.seekTo(positionMillis);

            updatePlaybackState(mediaPlayer.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
            updateNotification();
        }
    }

    /**
     * Sets the repeat mode of the MediaPlayer.
     * @param repeat true for repeat, false for no repeat.
     */
    public void setRepeat(int repeat) {
        repeatState = repeat;
    }

    // --- State Query Methods ---

    /**
     * Checks if media is currently playing.
     * @return true if playing, false otherwise.
     */
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    /**
     * Gets the index of the currently playing song in the playlist.
     * @return The index, or -1 if nothing is loaded/playing.
     */
    public int getCurrentSongIndex() {
        return currentlyPlayingPosition;
    }

    /**
     * Gets the current playback position in milliseconds.
     * @return Current position or 0 if not available.
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return Math.max(mediaPlayer.getCurrentPosition(), 0);
            } catch (IllegalStateException ignore) {}
        }
        return 0;
    }

    /**
     * Gets the duration of the current track in milliseconds.
     * @return Duration or 0 if not available.
     */
    public int getDuration() {
        if (mediaPlayer != null && isPrepared) {
            try {
                return Math.max(mediaPlayer.getDuration(), 0);
            } catch (IllegalStateException ignore) {}
        }
        return 0;
    }

    /**
     * Checks if service is initialized.
     * @return true if yes, false otherwise.
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    // --- Internal Playback Logic ---

    /**
     * Initializes the MediaPlayer instance if it doesn't exist.
     * Sets essential properties like wake lock and audio attributes, and attaches listeners.
     */
    private void initializeMediaPlayer() {
        Log.d(TAG, "Initializing new MediaPlayer instance.");

        // Release any existing player first to be safe
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());

        // Set listeners for completion, preparation, and errors
        mediaPlayer.setOnCompletionListener(completionListener);
        mediaPlayer.setOnPreparedListener(preparedListener);
        mediaPlayer.setOnErrorListener(errorListener);
    }

    /**
     * Prepares the MediaPlayer for playback of the track at the given position.
     * Handles creation/resetting of the MediaPlayer, checks for problematic songs,
     * sets the data source, and starts asynchronous preparation.
     *
     * @param position The index of the song in songList to prepare.
     */
    private void prepareAndPlayTrack(int position) {
        // Validate position
        if (position < 0 || position >= songList.size()) {
            stopServiceAndReleaseResources(); // Stop playback if position is invalid
            return;
        }

        currentlyPlayingPosition = position;

        // Ensure MediaPlayer instance exists, create if null
        if (mediaPlayer == null) {
            initializeMediaPlayer();
            if (mediaPlayer == null) { // Check if initialization failed somehow
                return;
            }
        } else {
            mediaPlayer.reset();
        }
        mediaPlayer.setVolume(1.0f, 1.0f);
        isPrepared = false;

        Song currentSong = songList.get(currentlyPlayingPosition);


        // Check if all songs have been marked as problematic
        if (problemSongTitles.size() >= songList.size() && !songList.isEmpty()) {
            Log.e(TAG, "All songs in the playlist are marked as problematic!");
            stopServiceAndReleaseResources(); // Stop everything if all songs failed
            return;
        }

        // Check if the current song is known to be problematic
        if (problemSongTitles.contains(currentSong.getTitle())) {
            Log.w(TAG, "Skipping problematic song: " + currentSong.getTitle());
            progressHandler.post(this::next);
            return;
        }

        try {
            mediaPlayer.setDataSource(this, currentSong.getAudioUri());
            mediaPlayer.prepareAsync();

        } catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Error setting data source or preparing MediaPlayer for: " + currentSong.getTitle(), e);

            problemSongTitles.add(currentSong.getTitle());
            isPrepared = false;

            // Attempt to play the next song
            progressHandler.post(this::next);
        }
    }


    // --- MediaSession and Notification Updates ---

    /**
     * Updates the MediaSession's playback state.
     * @param state The PlaybackStateCompat constant (e.g., STATE_PLAYING, STATE_PAUSED).
     */
    private void updatePlaybackState(int state) {
        // Get the current playback position, default to 0 if unavailable
        long position = 0;
        if (mediaPlayer != null && isPrepared) {
            position = mediaPlayer.getCurrentPosition();
        }

        // Build the PlaybackState object
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE | // Combined play/pause action
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_STOP |       // Indicate stop is supported
                                PlaybackStateCompat.ACTION_SEEK_TO      // Indicate seek is supported
                )
                // Set the current state, position, and playback speed (1.0f for normal)
                .setState(state, position, 1.0f);

        // Set the state on the MediaSession
        if(mediaSession != null) mediaSession.setPlaybackState(stateBuilder.build());
    }

    /**
     * Updates the MediaSession's metadata with information about the current song.
     * Loads the album art asynchronously.
     * @param song The current Song object.
     */
    private void updateMetadata(Song song) {
        if (song == null) {
            mediaSession.setMetadata(null); // Clear metadata if song is null
            return;
        }

        // Start building the metadata
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, song.getTitle())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration());

        // Set the metadata initially without the album art (which might take time to load)
        mediaSession.setMetadata(builder.build());

        // Asynchronously load the album art thumbnail
        ThumbnailLoader.loadThumbnailAsync(song, getApplicationContext(), bitmap -> {
            // Add the loaded bitmap to the metadata builder
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
            mediaSession.setMetadata(builder.build());

            // Update the notification as metadata change might affect its appearance
            updateNotification();
        });
    }

    /**
     * Builds the notification used for the foreground service and media controls.
     * Uses MediaStyle for rich media integration.
     * @return The configured Notification object.
     */
    private Notification buildMediaStyleNotification() {
        // Check if playlist is valid and position is within bounds
        if (songList.isEmpty() || currentlyPlayingPosition < 0 || currentlyPlayingPosition >= songList.size()) {
            Log.e(TAG, "Cannot build notification: Invalid state (empty list or bad position).");
            // Return a minimal or default notification?
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("No track loaded")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }

        Song currentSong = songList.get(currentlyPlayingPosition);
        boolean isPlaying = isPlaying(); // Check current playback state


        // --- Create PendingIntents for notification actions ---
        // These intents will trigger onStartCommand in this service with specific actions
        PendingIntent playIntent = PendingIntent.getService(this, 101,
                new Intent(this, MediaPlayerService.class).setAction(ACTION_PLAY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent pauseIntent = PendingIntent.getService(this, 102,
                new Intent(this, MediaPlayerService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent nextIntent = PendingIntent.getService(this, 103,
                new Intent(this, MediaPlayerService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevIntent = PendingIntent.getService(this, 104,
                new Intent(this, MediaPlayerService.class).setAction(ACTION_PREV),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopIntent = PendingIntent.getService(this, 105, // Unique request code
                new Intent(this, MediaPlayerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // --- Build the Notification ---
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID);

        // Basic notification properties
        builder.setContentTitle(currentSong.getTitle())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(ThumbnailLoader.loadThumbnailNonNullSync(currentSong, getApplicationContext()))
                .setDeleteIntent(stopIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
                .setOngoing(isPlaying);

        // Add media control actions
        builder.addAction(R.drawable.baseline_skip_previous_24, "Previous", prevIntent); // Previous button
        builder.addAction(isPlaying ? R.drawable.baseline_pause_circle_outline_24 : R.drawable.baseline_play_circle_outline_24, // Play/Pause button
                isPlaying ? "Pause" : "Play",
                isPlaying ? pauseIntent : playIntent);
        builder.addAction(R.drawable.baseline_skip_next_24, "Next", nextIntent);       // Next button
        builder.addAction(R.drawable.baseline_close_24, "Stop", stopIntent);

        // Apply MediaStyle
        androidx.media.app.NotificationCompat.MediaStyle mediaStyle = new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken()) // Link notification to MediaSession
                .setShowActionsInCompactView(0, 1, 2) // Indices of actions to show in compact view (Prev, Play/Pause, Next)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopIntent);


        builder.setStyle(mediaStyle);

        // Only show progress if duration is known (player is prepared)
        int duration = getDuration();
        int position = getCurrentPosition();
        if (duration > 0) {
            builder.setProgress(duration, position, false); // max, progress, indeterminate
        }

        // Set the intent to launch when the notification body is clicked
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(contentPendingIntent);

        return builder.build();
    }

    /**
     * Updates the foreground notification if permissions are granted.
     * Builds a new notification reflecting the current state and posts it.
     */
    private void updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "updateNotification - POST_NOTIFICATIONS permission denied. Cannot update notification.");
            return;
        }

        // Build the notification based on the current state
        Notification notification = buildMediaStyleNotification();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    // --- Resource Cleanup ---

    /**
     * Releases all significant resources held by the service.
     * Called from onDestroy() or when stopping the service explicitly.
     */
    private void releaseResources() {
        Log.d(TAG, "Releasing resources...");

        // Stop foreground state and remove notification
        stopForeground(true);

        // Release MediaPlayer
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Exception during MediaPlayer release", e);
            }
            isPrepared = false;
        }

        // Release MediaSession
        if (mediaSession != null) {
            mediaSession.setCallback(null);
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        // Abandon audio focus
        if (audioManager != null && afChangeListener != null) {
            audioManager.abandonAudioFocus(afChangeListener);
        }

        // Stop progress updates and clear handler messages
        progressHandler.removeCallbacksAndMessages(null);

        // Unregister broadcast receivers
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Noisy receiver was already unregistered.");
        }

        // Clear playlist and state
        songList.clear();
        problemSongTitles.clear();
        currentlyPlayingPosition = -1;

        Log.d(TAG, "Resource release complete.");
    }
}
