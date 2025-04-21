package com.example.tunestacker2.Pages;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.tunestacker2.Data.TempDataHolder;
import com.example.tunestacker2.MusicPlayer.MediaPlayerService;
import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.MusicPlayer.ThumbnailLoader;
import com.example.tunestacker2.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * MediaPlayerFragment displays the main media player interface, including song details,
 * playback controls, and the current playlist. It interacts with {@link MediaPlayerService}
 * to manage playback state and updates the UI accordingly.
 */
public class MediaPlayerFragment extends Fragment {
    // --- Constants ---
    private static final String TAG = "MediaPlayerFragment";
    private static final String ARG_SONG_LIST = "arg_song_list";
    private static final String ARG_SONG_LIST_KEY = "arg_song_list_key";
    private static final String ARG_CURRENT_SONG_INDEX = "arg_current_song_index";
    private static final String ARG_REPEAT_STATE = "arg_repeat_state";
    private static final int MAX_BUNDLE_SIZE_BYTES = 1024 * 1024; // 1MB

    // --- Data ---
    private ArrayList<Song> songList;  // Use ArrayList for Parcelable list
    private int currentSongIndex;
    private boolean isInvalidState = false;

    // --- Playback State ---
    private int repeatState = 2; // 0 = off, 1 = repeat one, 2 = repeat all
    private boolean isPlaying = false;

    // --- Service Connection ---
    private ServiceConnection serviceConnection;
    private boolean isServiceBound = false;
    private MediaPlayerService mediaPlayerService;

    // --- UI Elements ---
    private View playerContainer;
    private RecyclerView recyclerView;
    private MediaPlayerAdapter playerAdapter;
    private ImageButton btnBack;
    private TextView albumTitle;
    private com.google.android.material.imageview.ShapeableImageView albumArt;
    private TextView songTitle;

    private TextView startTime;
    private TextView endTime;
    private SeekBar seekBar;
    private ImageButton btnRepeat;
    private ImageButton btnPrev;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;
    private ImageButton btnShuffle;

    // --- UI Update Handling ---
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isClosing = false;

    /**
     * Runnable responsible for periodically fetching playback state from the
     * {@link MediaPlayerService} and updating the UI accordingly (SeekBar, time displays,
     * play/pause button, current song).
     */
    private final Runnable updateUIFromServiceRunnable = new Runnable() {
        @Override
        public void run() {
            // Check if the service is still running. If not, close the player.
            if (!MediaPlayerService.isServiceRunning) {
                Log.d(TAG, "Service is no longer running, closing player.");
                closeWithAnimation(); // Initiate closing if the service stopped unexpectedly.
                return;
            }

            // Update UI elements only if the service is bound and available.
            if (isServiceBound && mediaPlayerService != null) {
                // Update Seekbar and Time Displays
                int current_time = mediaPlayerService.getCurrentPosition();
                int duration = mediaPlayerService.getDuration();
                startTime.setText(convertTimeUnitsToString(current_time));
                endTime.setText(convertTimeUnitsToString(duration));
                seekBar.setMax(duration);
                seekBar.setProgress(current_time);

                // Update Song Information if Changed
                int serviceSongIndex = mediaPlayerService.getCurrentSongIndex();
                if (serviceSongIndex != currentSongIndex && serviceSongIndex >= 0 && serviceSongIndex < songList.size()) {
                    currentSongIndex = serviceSongIndex;
                    updateSongUIDisplay(); // Update title, artwork, and highlight in RecyclerView
                }

                // Update Play/Pause Button State if Changed
                boolean serviceIsPlaying = mediaPlayerService.isPlaying();
                if (isPlaying != serviceIsPlaying) {
                    updatePlayPauseButton(serviceIsPlaying);
                    isPlaying = serviceIsPlaying; // Update local state
                }
            }

            // Schedule the next UI update after a short delay (500ms).
            handler.postDelayed(this, 500);
        }
    };

    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public MediaPlayerFragment() {}

    /**
     * Factory method to create a new instance of this fragment
     * with the provided song list and starting position.
     *
     * @param songs The list of {@link Song} objects to play.
     * @param pos   The index of the song in the list to start playback from.
     * @return A new instance of fragment MediaPlayerFragment.
     */
    public static MediaPlayerFragment newInstance(List<Song> songs, int pos, int state) {
        MediaPlayerFragment fragment = new MediaPlayerFragment();
        Bundle args = new Bundle();

        // Try putting into bundle
        ArrayList<Song> songList = new ArrayList<>(songs);
        args.putParcelableArrayList(MediaPlayerFragment.ARG_SONG_LIST, songList);
        args.putInt(MediaPlayerFragment.ARG_CURRENT_SONG_INDEX, pos);
        args.putInt(MediaPlayerFragment.ARG_REPEAT_STATE, state);

        // Check size to ensure it doesn't exceed the limit
        Parcel parcel = Parcel.obtain();
        args.writeToParcel(parcel, 0);
        int sizeInBytes = parcel.dataSize();
        parcel.recycle();

        if (sizeInBytes > MAX_BUNDLE_SIZE_BYTES) {
            Log.w("MediaPlayerFragment", "Bundle too large (" + sizeInBytes + " bytes). Using fallback.");

            // Fallback: remove song list from bundle
            args.remove(MediaPlayerFragment.ARG_SONG_LIST);

            // Generate a unique key and store in temp holder
            String dataKey = UUID.randomUUID().toString();
            TempDataHolder.put(dataKey, songs);
            args.putString(ARG_SONG_LIST_KEY, dataKey);
        }

        fragment.setArguments(args);
        return fragment;
    }

    // --- Lifecycle Methods ---

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            Bundle args = getArguments();
            songList = args.getParcelableArrayList(ARG_SONG_LIST);

            // Check fallback key if list is null
            if (songList == null) {
                String dataKey = args.getString(ARG_SONG_LIST_KEY);
                if (dataKey != null) {
                    songList = new ArrayList<>(TempDataHolder.get(dataKey));
                    TempDataHolder.remove(dataKey); // Clean up after retrieving
                }
            }

            currentSongIndex = args.getInt(ARG_CURRENT_SONG_INDEX);
            repeatState = args.getInt(ARG_REPEAT_STATE);

            if (currentSongIndex < 0 || currentSongIndex >= songList.size()) {
                currentSongIndex = 0;
            }
            if (repeatState < 0 || repeatState > 2) {
                repeatState = 2;
            }

            if (songList == null || songList.isEmpty()) {
                Log.e(TAG, "Song list argument was null or empty!");
                isInvalidState = true;
                return;
            }

            setupServiceConnection();
        } else {
            Log.e(TAG, "Song list argument was null!");
            isInvalidState = true;
        }
    }



    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_media_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (isInvalidState) {
            view.post(() -> requireActivity().getSupportFragmentManager().popBackStack());
            return;
        }

        // Find and assign all UI element references.
        initializeViews(view);

        // Set up listeners for all interactive UI elements.
        setupUIListeners();

        // Configure the RecyclerView to display the playlist.
        setupRecyclerView();

        // Set up the custom behavior for the system back button.
        setupBackButtonOverride();

        // Animate the fragment container into view.
        animateFragmentIn();

        // Perform the initial UI update based on the starting song.
        updateSongUIDisplay();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(requireContext().getApplicationContext(), MediaPlayerService.class);
        if (!isServiceBound) {
            requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }

        handler.post(updateUIFromServiceRunnable);
    }

    @Override
    public void onStop() {
        super.onStop();
        handler.removeCallbacks(updateUIFromServiceRunnable);
        if (isServiceBound) {
            requireActivity().unbindService(serviceConnection);
            isServiceBound = false;
        }
    }

    // --- Initialization and Setup Methods ---

    /**
     * Finds and assigns all view references from the inflated layout.
     *
     * @param view The root view of the fragment's layout.
     */
    private void initializeViews(@NonNull View view) {
        playerContainer = requireActivity().findViewById(R.id.playerContainer); // Assumes playerContainer is in the Activity layout
        albumTitle = view.findViewById(R.id.albumTitle);
        albumArt = view.findViewById(R.id.albumArt);
        seekBar = view.findViewById(R.id.seekBar);
        startTime = view.findViewById(R.id.startTime);
        endTime = view.findViewById(R.id.endTime);
        songTitle = view.findViewById(R.id.songTitle);
        songTitle.setSelected(true); // Enable marquee effect for long titles

        btnBack = view.findViewById(R.id.btnBack);
        btnPlayPause = view.findViewById(R.id.btnPlayPause);
        btnNext = view.findViewById(R.id.btnNext);
        btnPrev = view.findViewById(R.id.btnPrev);
        btnShuffle = view.findViewById(R.id.btnShuffle);
        btnRepeat = view.findViewById(R.id.btnRepeat);
        recyclerView = view.findViewById(R.id.playlistRecyclerView);

        // Set initial repeat button color based on the default state
        updateRepeatButtonVisual(repeatState);
    }

    /**
     * Sets up OnClickListeners and other listeners for UI elements.
     */
    private void setupUIListeners() {
        // Back button closes the fragment with animation.
        btnBack.setOnClickListener(v -> closeWithAnimation());

        // Play/Pause button toggles playback in the service.
        btnPlayPause.setOnClickListener(v -> {
            if (isServiceBound && mediaPlayerService != null) {
                if (mediaPlayerService.isPlaying()) {
                    // Manually update UI immediately for responsiveness.
                    updatePlayPauseButton(false);
                    mediaPlayerService.pause();
                } else {
                    // Manually update UI immediately for responsiveness.
                    updatePlayPauseButton(true);
                    mediaPlayerService.play();
                }
            }
        });

        // Next button tells the service to play the next song.
        btnNext.setOnClickListener(v -> {
            if (isServiceBound && mediaPlayerService != null) {
                int result = mediaPlayerService.next();
                if (result != -1 && result != currentSongIndex && result < songList.size()) {
                    currentSongIndex = result;
                    updateSongUIDisplay();
                }
            }
        });

        // Previous button tells the service to play the previous song.
        btnPrev.setOnClickListener(v -> {
            if (isServiceBound && mediaPlayerService != null) {
                int result = mediaPlayerService.previous();
                if (result != -1 && result != currentSongIndex && result < songList.size()) {
                    currentSongIndex = result;
                    updateSongUIDisplay();
                }
            }
        });

        // Shuffle button shuffles the playlist and updates the service.
        btnShuffle.setOnClickListener(v -> {
            if (isServiceBound && mediaPlayerService != null) {
                shufflePlaylist();
                updateSongUIDisplay();
                mediaPlayerService.setPlaylist(songList, currentSongIndex);
            }
        });

        // Repeat button toggles repeat mode in the service.
        btnRepeat.setOnClickListener(v -> {
            if (isServiceBound && mediaPlayerService != null) {
                repeatState = (repeatState + 1) % 3;
                updateRepeatButtonVisual(repeatState);
                mediaPlayerService.setRepeat(repeatState);
            }
        });

        // SeekBar allows the user to seek within the current track.
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // If the change is from the user touching the seek bar, tell the service to seek.
                if (fromUser && isServiceBound && mediaPlayerService != null) {
                    mediaPlayerService.SeekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Optional: Can pause updates while tracking, but current implementation handles it.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Optional: Can resume updates if paused in onStartTrackingTouch.
            }
        });
    }

    /**
     * Configures the RecyclerView, its LayoutManager, and Adapter.
     */
    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));
        playerAdapter = new MediaPlayerAdapter(requireContext(), songList, currentSongIndex, new MediaPlayerAdapter.MediaPlayerAdapterListener() {
            @Override
            public void onSongClicked(int pos) {
                // When a song in the list is clicked, tell the service to skip to that song.
                if (isServiceBound && mediaPlayerService != null && pos != currentSongIndex) {
                    currentSongIndex = pos;
                    updateSongUIDisplay();

                    mediaPlayerService.skipTo(pos);
                }
            }
        });
        recyclerView.setAdapter(playerAdapter);

        // Set animation speed
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator != null) {
            animator.setChangeDuration(50);
        }

    }

    /**
     * Sets up the ServiceConnection callbacks to manage the bound service lifecycle. Also starts
     * the foreground service.
     */
    private void setupServiceConnection() {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                MediaPlayerService.LocalBinder localBinder = (MediaPlayerService.LocalBinder) binder;
                mediaPlayerService = localBinder.getService();
                isServiceBound = true;

                // On first time startup
                if(!mediaPlayerService.isInitialized()) {
                    mediaPlayerService.setPlaylist(songList, currentSongIndex);
                    mediaPlayerService.setRepeat(repeatState);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mediaPlayerService = null;
                isServiceBound = false;
            }
        };

        // Start the foreground service
        Intent serviceIntent = new Intent(requireContext().getApplicationContext(), MediaPlayerService.class);
        ContextCompat.startForegroundService(requireContext().getApplicationContext(), serviceIntent);
    }

    /**
     * Overrides the default back button behavior to close the fragment with an animation.
     */
    private void setupBackButtonOverride() {
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), // Ensures the callback is only active when the view is alive
                new OnBackPressedCallback(true) { // 'true' means this callback is enabled
                    @Override
                    public void handleOnBackPressed() {
                        closeWithAnimation();
                    }
                });
    }

    /**
     * Stops the MediaPlayerService and unbinds from it.
     * Typically called when the player UI is being permanently closed.
     */
    private void stopAndUnbindService() {
        if (isServiceBound) {
            if (mediaPlayerService != null) {
                mediaPlayerService.stopSelf(); // Your safe shutdown method
            }

            requireActivity().unbindService(serviceConnection);
            isServiceBound = false;
        }
    }

    // --- UI Update and Animation ---

    /**
     * Animates the player container sliding up into view.
     */
    private void animateFragmentIn() {
        // Ensure playerContainer is not null before proceeding.
        if (playerContainer == null) {
            Log.e("MediaPlayerFragment", "playerContainer is null in animateFragmentIn!");
            return;
        }

        // Calculate screen height for the starting position of the animation.
        int screenHeight = getScreenHeight();

        // Start slightly off-screen (e.g., 20% down) and invisible/gone
        playerContainer.setTranslationY(screenHeight * 0.2f); // Start position
        playerContainer.setVisibility(View.VISIBLE); // Make it visible before animation starts

        // Animate translationY to 0 (its original position).
        playerContainer.animate()
                .translationY(0)
                .setDuration(250) // Animation duration in milliseconds
                .setInterpolator(new DecelerateInterpolator()) // Smoother easing
                .start();
    }

    /**
     * Animates the player container sliding down and out of view, then removes the fragment.
     */
    private void closeWithAnimation() {
        if(isClosing) return;
        isClosing = true; // Prevent re-entry

        // Fallback if playerContainer is null
        if (playerContainer == null && isAdded()) {
            requireActivity().getSupportFragmentManager().popBackStack();
            return;
        }

        handler.removeCallbacks(updateUIFromServiceRunnable);
        stopAndUnbindService();

        playerContainer.animate()
                .translationY(playerContainer.getHeight())
                .setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (playerContainer != null) playerContainer.setVisibility(View.GONE);
                    isClosing = false; // Reset flag just in case fragment is reused
                    if (isAdded()) {
                        requireActivity().getSupportFragmentManager().popBackStack();
                    }
                })
                .start();
    }

    /**
     * Shuffles the `songList` in place, keeping the currently selected song at the top (index 0).
     * Updates `currentSongIndex` to 0 and notifies the RecyclerView adapter.
     */
    private void shufflePlaylist() {
        if (songList == null || songList.size() <= 1 || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
            return; // Nothing to shuffle or invalid input
        }

        Song selectedItem = songList.get(currentSongIndex);
        List<Song> temp = new ArrayList<>(songList);
        temp.remove(currentSongIndex);

        Collections.shuffle(temp);

        songList.clear();
        songList.add(selectedItem);
        songList.addAll(temp);

        currentSongIndex = 0;
        recyclerView.getAdapter().notifyDataSetChanged();
    }

    /**
     * Updates the visual state of the Play/Pause button.
     * @param showPause If true, show the Pause icon; otherwise, show the Play icon.
     */
    private void updatePlayPauseButton(boolean showPause) {
        if (btnPlayPause != null) {
            if (showPause) {
                btnPlayPause.setImageResource(R.drawable.baseline_pause_circle_outline_24);
            } else {
                btnPlayPause.setImageResource(R.drawable.baseline_play_circle_outline_24);
            }
        }
    }

    /**
     * Updates the visual state (color filter) of the Repeat button.
     * @param repeat If true, tint the button to the primary color; otherwise, use default white.
     */
    private void updateRepeatButtonVisual(int repeat) {
        if (btnRepeat != null && getContext() != null) {
            if (repeat == 0) {
                btnRepeat.setColorFilter(ContextCompat.getColor(requireContext(), R.color.white)); // Assuming white is the default/inactive color
                btnRepeat.setImageResource(R.drawable.baseline_repeat_24);
            } else if(repeat == 1) {
                btnRepeat.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary_theme));
                btnRepeat.setImageResource(R.drawable.baseline_repeat_one_24px);
            }
            else {
                btnRepeat.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary_theme));
                btnRepeat.setImageResource(R.drawable.baseline_repeat_24);
            }
        }
    }

    /**
     * Updates the main UI elements (song title, album art) and RecyclerView selection
     * based on the `currentSongIndex`.
     */
    private void updateSongUIDisplay() {
        if(currentSongIndex < 0 || songList == null ||
                currentSongIndex >= songList.size() || getContext() == null) {
            return;
        }
        // Ensure that the views are still there
        if(recyclerView == null || playerAdapter == null || songTitle == null || albumArt == null) {
            return;
        }

        // Update UI for recycler
        playerAdapter.selectSong(currentSongIndex);
        recyclerView.scrollToPosition(currentSongIndex);

        // Update UI elements for the current song
        Song song = songList.get(currentSongIndex);
        songTitle.setText(song.getTitle());

        albumArt.setImageBitmap(ThumbnailLoader.loadThumbnailSync(song, requireContext().getApplicationContext()));

        // Loads higher resolution image
        ThumbnailLoader.loadLargeThumbnailAsync(song, requireContext().getApplicationContext(), bitmap -> {
            if (albumArt != null) albumArt.setImageBitmap(bitmap);
        });
    }

    // --- Utility Methods ---

    /**
     * Converts a time duration in milliseconds to a "M:SS" formatted string.
     *
     * @param timeMillis The time duration in milliseconds.
     * @return A formatted string representation (e.g., "3:45").
     */
    public static String convertTimeUnitsToString(int timeMillis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(timeMillis);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(timeMillis) -
                TimeUnit.MINUTES.toSeconds(minutes);
        // Use Locale.US to ensure ':' separator and standard digits.
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /**
     * Gets the screen height in pixels. Handles different Android versions.
     *
     * @return The screen height in pixels.
     */
    private int getScreenHeight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern way for Android R (API 30) and above.
            WindowMetrics metrics = requireActivity().getWindowManager().getCurrentWindowMetrics();
            return metrics.getBounds().height();
        } else {
            // Deprecated way for older Android versions.
            DisplayMetrics dm = new DisplayMetrics();
            requireActivity().getWindowManager().getDefaultDisplay().getMetrics(dm);
            return dm.heightPixels;
        }
    }
}