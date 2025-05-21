package com.example.tunestacker2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.viewpager2.widget.ViewPager2;

import com.example.tunestacker2.Data.DataManager;
import com.example.tunestacker2.Data.ForegroundDownloadService;
import com.example.tunestacker2.MusicPlayer.Playlist;
import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.Pages.LibraryFragment;
import com.example.tunestacker2.Pages.MainActivityViewPagerAdapter;
import com.example.tunestacker2.Pages.MediaPlayerFragment;
import com.example.tunestacker2.Pages.PlaylistEditorFragment;
import com.example.tunestacker2.Pages.PlaylistFragment;
import com.example.tunestacker2.Pages.SettingsFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.yausername.youtubedl_android.YoutubeDL;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The main activity of the TuneStacker application.
 * Handles navigation between fragments (Library, Playlist, Settings) using a ViewPager2 and TabLayout.
 * Manages background image transitions, download requests, update checks, and permission handling.
 */
public class MainActivity extends AppCompatActivity implements LibraryFragment.LibraryFragmentRequestsListener, SettingsFragment.UpdateRequestListener, PlaylistFragment.PlaylistFragmentListener, PlaylistEditorFragment.PlaylistEditorFragmentListener {
    // Constants
    private static final String TAG = "MainActivity"; // Tag for logging
    private static final long UPDATE_CHECK_COOLDOWN_MILLISECONDS = 1000 * 60 * 60 * 24 * 7; // 7 days in milliseconds
    private static final int MULTIPLE_PERMISSIONS_CODE = 1002; // Changed request code to avoid conflict


    // UI elements
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private ImageView backgroundImageView;

    // Dialog related UI elements for download progress
    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView mainTextView;
    private TextView contentTextView;

    // BroadcastReceiver for download progress updates
    private BroadcastReceiver downloadReceiver;


    // Data for tabs
    private static final String[] TAB_TITLES = {"Library", "Playlists", "Settings"};
    private static final int[] TAB_ICONS = {
            R.drawable.baseline_library_music_24,
            R.drawable.baseline_playlist_play_24,
            R.drawable.baseline_settings_24
    };
    // Corresponding background drawables for each tab
    private static final int[] TAB_BACKGROUNDS = {
            R.drawable.gpt_1_blurred_small,
            R.drawable.fapka_4_blurred_small,
            R.drawable.dark_solid
    };


    // --- Activity Lifecycle Methods ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge display
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize UI components
        initializeUI();

        // Setup ViewPager and TabLayout for navigation
        setupViewPagerAndTabs();

        // Register download popup update broadcasts
        setupBroadcastDownloadReceiver();

        // Request permissions, returns false if permissions are already granted
        if (!requestMultiplePermissions()) {
            checkAndPerformAutoUpdate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the broadcast receiver to prevent memory leaks
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
        // Dismiss any showing dialogs to avoid window leaks
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    // --- Initialization Methods ---

    /**
     * Finds and assigns UI elements from the layout.
     */
    private void initializeUI() {
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        backgroundImageView = findViewById(R.id.backgroundImageView);
        backgroundImageView.setImageResource(TAB_BACKGROUNDS[0]);
    }

    /**
     * Sets up the ViewPager2 adapter, connects it with the TabLayout,
     * and registers a callback for background changes on page selection.
     */
    private void setupViewPagerAndTabs() {
        // Create and set the adapter for the ViewPager
        MainActivityViewPagerAdapter adapter = new MainActivityViewPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // Use TabLayoutMediator to link TabLayout and ViewPager2
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> {
                    // Inflate custom layout for each tab
                    View customTab = LayoutInflater.from(this).inflate(R.layout.custom_tab, null);
                    ImageView icon = customTab.findViewById(R.id.tab_icon);
                    TextView text = customTab.findViewById(R.id.tab_text);

                    // Set icon and text based on position
                    icon.setImageResource(TAB_ICONS[position]);
                    text.setText(TAB_TITLES[position]);

                    // Set the custom view for the tab
                    tab.setCustomView(customTab);
                }
        ).attach(); // Attach the mediator to connect the components

        // Register a callback to listen for page changes in the ViewPager
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                // Change background with crossfade animation when page changes
                crossfadeBackground(TAB_BACKGROUNDS[position]);
            }
        });
    }

    /**
     * Initializes and registers the BroadcastReceiver for download progress updates
     * from {@link ForegroundDownloadService}.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Flag needed for older SDKs clarified by version check
    private void setupBroadcastDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Extract data from the broadcast intent
                int progress = intent.getIntExtra(ForegroundDownloadService.INTENT_PROGRESS_KEY, 0);
                String mainText = intent.getStringExtra(ForegroundDownloadService.INTENT_MAIN_TEXT_KEY);
                String contentText = intent.getStringExtra(ForegroundDownloadService.INTENT_CONTENT_TEXT_KEY);
                boolean terminate = intent.getBooleanExtra(ForegroundDownloadService.INTENT_TERMINATE_KEY, false);

                // Update the UI of the download progress popup
                updateDownloadProgressPopupUIDisplay(mainText, contentText, progress);

                if (terminate) {
                    // Dismiss the progress dialog
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }

                    // Show a toast message indicating the final status
                    Toast.makeText(context, mainText, Toast.LENGTH_SHORT).show();
                    updateLibraryFragmentRecyclerView();
                }
            }
        };

        // Define the intent filter for the broadcast receiver
        IntentFilter filter = new IntentFilter(ForegroundDownloadService.BROADCAST_ACTION_IDENTIFIER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13 (API 33) and above, specify RECEIVER_NOT_EXPORTED for security
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    /**
     * Checks if the update cooldown period has passed and triggers an update check if needed.
     */
    private void checkAndPerformAutoUpdate() {
        // Get the timestamp of the last update check
        long lastUpdateTimestamp = DataManager.getInstance().getLastUpdated();
        long currentTimestamp = new Date().getTime();

        if (Math.abs(currentTimestamp - lastUpdateTimestamp) >= UPDATE_CHECK_COOLDOWN_MILLISECONDS) {
            Log.i(TAG, "Update cooldown elapsed. Performing automatic update check.");
            onUpdateRequested();
        } else {
            Log.i(TAG, "Automatic update check skipped, within cooldown period.");
        }
    }

    // --- Interface Implementations (Callbacks from Fragments) ---

    /**
     * Callback method from {@link LibraryFragment} when a download is requested.
     * Starts the {@link ForegroundDownloadService} to handle the download.
     *
     * @param url The URL of the media to download.
     */
    @Override
    public void onDownloadRequested(String url) {
        // Prevent starting a new download if one is already in progress (dialog is showing)
        if (progressDialog != null && progressDialog.isShowing()) {
            Log.w(TAG, "Download request ignored: Another download is already in progress.");
            Toast.makeText(this, "Another download is in progress.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Display the floating progress popup
        showFloatingProgressPopup();

        // Create an intent to start the foreground download service
        Intent intent = new Intent(getApplicationContext(), ForegroundDownloadService.class);
        intent.putExtra(ForegroundDownloadService.INTENT_URL_KEY, url);
        ContextCompat.startForegroundService(getApplicationContext(), intent);
    }

    /**
     * Callback method from {@link LibraryFragment} when the media player is requested.
     *
     * @param songs The List of songs for the playlist.
     * @param pos   The index of the song to play.
     * @param repeatState The repeat state of the playlist.
     */
    @Override
    public void onLaunchMediaPlayer(List<Song> songs, int pos, int repeatState) {
        if(songs == null || songs.isEmpty()) return;
        pos = Math.max(0, Math.min(pos, songs.size() - 1));

        // Launch the MediaPlayerFragment
        MediaPlayerFragment fragment = MediaPlayerFragment.newInstance(songs, pos, repeatState);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.playerContainer, fragment)
                .addToBackStack(null)
                .commit();
    }

    /**
     * Callback method from {@link SettingsFragment} when a manual Yt-Dlp update is requested,
     * or called automatically after the cooldown period.
     * Shows an update dialog and performs the update check in a background thread.
     */
    @Override
    public void onUpdateRequested() {
        // Inflate custom view
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_updating_popup, null);
        AlertDialog update_dialog = new AlertDialog.Builder(this, R.style.CustomDialog)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        update_dialog.setCanceledOnTouchOutside(false);
        update_dialog.show();

        // Run updater in background thread
        Executors.newSingleThreadExecutor().execute(() -> {
            int update_status = -1;
            try {
                YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                if(status == YoutubeDL.UpdateStatus.DONE) {
                    update_status = 2;
                }
                else {
                    update_status = 1;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error performing update check", e);
            }
            // Updates last updated time for the auto updater trigger
            DataManager.getInstance().setLastUpdated(new Date().getTime());


            // Dismiss dialog safely on UI thread
            final int final_update_status = update_status;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (update_dialog != null && update_dialog.isShowing()) {
                    update_dialog.dismiss();
                }

                if (final_update_status == 1) {
                    Toast.makeText(this, "Yt-Dlp Already up-to-date.", Toast.LENGTH_SHORT).show();
                } else if (final_update_status == 2) {
                    Toast.makeText(this, "Yt-Dlp Update Downloaded.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Yt-Dlp Update Failed.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * Callback method from {@link PlaylistFragment} when a playlist is clicked,
     */
    @Override
    public void onLaunchPlaylistEditor(Playlist playlist) {
        // Launch the Playlist Editor
        PlaylistEditorFragment fragment = PlaylistEditorFragment.newInstance(playlist);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.playlistEditorContainer, fragment)
                .addToBackStack(null)
                .commit();
    }

    /**
     * Callback method from {@link LibraryFragment} when a song is added to a playlist, refreshing
     * the PlaylistFragment page.
     */
    @Override
    public void requestPlaylistRefresh(boolean freshRefresh) {
        updatePlaylistFragmentRecyclerView(freshRefresh);
    }

    /**
     * Callback method from {@link SettingsFragment} when the directory changes, which means the
     * the pages must be forced to refresh.
     */
    @Override
    public void onDirectorySwitch() {
        updateLibraryFragmentRecyclerView();
        updatePlaylistFragmentRecyclerView(true);
    }

    // --- Download Progress Popup Methods ---

    /**
     * Creates and displays a floating, non-modal dialog to show download progress.
     * The dialog appears near the top of the screen and allows interaction with the underlying activity.
     */
    private void showFloatingProgressPopup() {
        // Create a new Dialog with a transparent theme
        progressDialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_download_progress, null);
        progressDialog.setContentView(view);
        progressDialog.setCanceledOnTouchOutside(true);

        // Find UI elements within the dialog layout
        progressBar = view.findViewById(R.id.progressBar);
        mainTextView = view.findViewById(R.id.progressTitle);
        contentTextView = view.findViewById(R.id.progressSubtext);
        Button cancelButton = view.findViewById(R.id.cancelButton);

        // Initialize progress bar
        progressBar.setProgress(0);
        progressBar.setMax(100);

        // Set up the cancel button listener
        cancelButton.setOnClickListener(v -> {
            stopService(new Intent(getApplicationContext(), ForegroundDownloadService.class));
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            Toast.makeText(this, "Download canceled.", Toast.LENGTH_SHORT).show();
        });

        // Configure the dialog window properties for floating behavior
        Window window = progressDialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP;
            params.y = 50; // Offset from top in pixels
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        progressDialog.show();
    }

    /**
     * Updates the UI elements (ProgressBar, TextViews) within the floating progress dialog.
     * Ensures progress value is clamped between 0 and 100.
     *
     * @param mainText    The main status text (e.g., "Downloading...").
     * @param contentText The secondary status text (e.g., filename or percentage).
     * @param progress    The download progress percentage (0-100).
     */
    private void updateDownloadProgressPopupUIDisplay(String mainText, String contentText, int progress) {
        // Check if the dialog and its views are valid before updating
        if (progressDialog == null || !progressDialog.isShowing()) return;

        if (progressBar != null) {
            int clampedProgress = Math.max(0, Math.min(100, progress));
            progressBar.setProgress(clampedProgress);
        }
        if (mainTextView != null && mainText != null) {
            mainTextView.setText(mainText);
        }
        if (contentTextView != null && contentText != null) {
            contentTextView.setText(contentText);
        }
    }

    // --- UI Update Methods ---

    /**
     * Finds the currently displayed {@link LibraryFragment} and calls its method
     * to refresh the list of songs, typically after a download completes.
     */
    private void updateLibraryFragmentRecyclerView() {
        // f0 is the tag for the LibraryFragment automatically created by the base class of adapter
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentByTag("f0");

        if (fragment != null && fragment.isAdded() && fragment instanceof LibraryFragment) {
            ((LibraryFragment) fragment).refreshSongList();
        }
    }

    /**
     * Finds the currently displayed {@link PlaylistFragment} and calls its method
     * to refresh the list of songs, typically after a download completes.
     */
    private void updatePlaylistFragmentRecyclerView(boolean freshRefresh) {
        // f1 is the tag for the PlaylistFragment automatically created by the base class of adapter
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentByTag("f1");

        if (fragment != null && fragment.isAdded() && fragment instanceof PlaylistFragment) {
            ((PlaylistFragment) fragment).refreshPlaylistList(freshRefresh);
        }
    }

    /**
     * Smoothly transitions the background image view from its current drawable
     * to a new one using a {@link TransitionDrawable}.
     *
     * @param newDrawableResId The resource ID of the drawable to transition to.
     */
    private void crossfadeBackground(int newDrawableResId) {
        if(backgroundImageView == null) return;
        Drawable previousDrawable = backgroundImageView.getDrawable();
        Drawable nextDrawable = ContextCompat.getDrawable(this, newDrawableResId);

        if (nextDrawable == null) {
            Log.e(TAG, "Failed to load next drawable for crossfade (ID: " + newDrawableResId + ")");
            return;
        }

        // If there's no previous drawable (e.g., first time setting), just set the new one directly.
        if (previousDrawable == null) {
            backgroundImageView.setImageDrawable(nextDrawable);
            return;
        }

        // Create a TransitionDrawable to animate the crossfade
        Drawable[] layers = new Drawable[]{previousDrawable, nextDrawable};
        TransitionDrawable transitionDrawable = new TransitionDrawable(layers);
        backgroundImageView.setImageDrawable(transitionDrawable);
        transitionDrawable.startTransition(250);
    }

    // --- Permissions Handling ---

    /**
     * Checks for required permissions based on the Android SDK version and requests
     * any that haven't been granted.
     * Permissions needed:
     * - Android 13+ (API 33+): READ_MEDIA_AUDIO, POST_NOTIFICATIONS
     * - Android 10-12 (API 29-32): READ_EXTERNAL_STORAGE
     * - Android 9 and below (API <= 28): READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE
     *
     * @return {@code true} if all required permissions are already granted, {@code false} otherwise (and requests permissions).
     */
    private boolean requestMultiplePermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: request media + notifications
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // API 32 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Only request WRITE_EXTERNAL_STORAGE on API 28 and below
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
        }

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), MULTIPLE_PERMISSIONS_CODE);
            return false;
        }
        return true;
    }

    /**
     * Callback received when the user responds to a permission request.
     * Handles the results and shows Toast messages indicating grant/denial status.
     *
     * @param requestCode  The request code passed to requestPermissions().
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MULTIPLE_PERMISSIONS_CODE) {
            boolean allGranted = true;

            // Iterate through the results
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                boolean granted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                String status = granted ? "granted" : "denied";

                // Show a toast for each permission result
                String permissionFriendlyName = getPermissionFriendlyName(permission);
                Toast.makeText(this, permissionFriendlyName + " permission " + status, Toast.LENGTH_SHORT).show();

                if (!granted) {
                    allGranted = false;
                    Log.w(TAG, permission + " was denied by the user.");
                }
            }

            // After checking all permissions, if all were granted, we might trigger actions
            if (allGranted) {
                Log.i(TAG, "All requested permissions were granted.");
                checkAndPerformAutoUpdate();
            } else {
                Log.w(TAG, "Not all requested permissions were granted.");
            }
        }
    }

    /**
     * Helper method to get a user-friendly name for a permission string.
     *
     * @param permission The permission string (e.g., Manifest.permission.READ_MEDIA_AUDIO).
     * @return A user-friendly name (e.g., "Audio Access").
     */
    private String getPermissionFriendlyName(String permission) {
        switch (permission) {
            case Manifest.permission.READ_EXTERNAL_STORAGE:
                return "Read Storage";
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return "Write Storage";
            case Manifest.permission.READ_MEDIA_AUDIO:
                return "Audio Access";
            case Manifest.permission.POST_NOTIFICATIONS:
                return "Notifications";
            default:
                return "Unknown Permission";
        }
    }
}