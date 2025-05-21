package com.example.tunestacker2.Pages;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.example.tunestacker2.Data.DataManager;
import com.example.tunestacker2.Data.FileUtils;
import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * A Fragment to display the user's music library.
 * Allows users to view, search, sort, play, delete, and add songs to playlists.
 * Also provides functionality to download new songs via URL.
 * Communicates with the hosting Activity via the {@link LibraryFragmentRequestsListener} interface.
 */

public class LibraryFragment extends Fragment {
// --- Interface for Communication with Hosting Activity ---

    /**
     * Interface definition for callbacks to the hosting Activity.
     * This allows the Fragment to request actions like downloads or media playback
     * without being tightly coupled to the specific Activity implementation.
     */
    public interface LibraryFragmentRequestsListener {
        /**
         * Called when the user requests to download a song from a URL.
         * @param url The URL provided by the user.
         */
        void onDownloadRequested(String url);

        /**
         * Called when the user selects a song or a group of songs to play.
         * @param songs The list of songs to play.
         * @param pos The starting position within the list.
         * @param repeatState The initial repeat state for the player (e.g., repeat all, repeat one).
         */
        void onLaunchMediaPlayer(List<Song> songs, int pos, int repeatState);

        /**
         * Called when playlists have been modified (e.g., songs added) and the
         * playlist UI potentially needs refreshing.
         */
        void requestPlaylistRefresh(boolean freshRefresh);
    }

    // --- Member Variables ---

    // UI Elements
    private SwipeRefreshLayout swipeRefreshLayout;
    private FloatingActionButton fabAddSong;
    private FloatingActionButton scrollToTopButton;
    private RecyclerView recyclerView;
    private ImageButton filterButton;
    private EditText searchBar;
    private View multiSelectMenuView;      // Reference to the inflated multi-select menu view
    private TextView multiSelectTextView;  // Text view within the multi-select menu to show count

    // Adapters and Data
    private LibraryAdapter songAdapter;
    private List<Song> fullSongList = new ArrayList<>();     // Holds all songs, unsorted initially, then sorted
    private List<Song> filteredSongList = new ArrayList<>(); // Holds songs currently displayed after filtering

    // State
    private int selectedSortId = 0; // 0: Alpha, 1: Newest, 2: Oldest. Persisted via DataManager.Settings.
    private LibraryFragmentRequestsListener listener; // Listener for activity communication
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;

    // --- Constructors and Lifecycle Methods ---

    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public LibraryFragment() {
        // Required empty public constructor
    }


    /**
     * Called when the fragment is first attached to its context.
     * Ensures the hosting Activity implements the required {@link LibraryFragmentRequestsListener}.
     * @param context The application context.
     */
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof LibraryFragmentRequestsListener) {
            listener = (LibraryFragmentRequestsListener) context;
        } else {
            throw new RuntimeException(context + " must implement LibraryFragmentRequestsListener");
        }
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     * @param inflater The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container If non-null, this is the parent view that the fragment's UI should be attached to.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state.
     * @return Return the View for the fragment's UI, or null.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    /**
     * Called immediately after {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)} has returned,
     * but before any saved state has been restored in to the view.
     * Initializes views, sets up RecyclerView, listeners, and loads initial data.
     * @param view The View returned by {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find all view elements by ID
        initializeViews(view);

        // Configure the RecyclerView and its adapter
        setupRecyclerView();

        // Set up listeners for buttons, search, refresh, etc.
        setupListeners(view);

        // Handle back presses, especially for multi-select mode
        setupBackPressedHandler();

        // Load the initial sort order preference
        selectedSortId = DataManager.Settings.GetSortOrder();

        // Load the initial list of songs
        refreshSongList();

        // Preload playlists from JSON files AFTER loading initial songs
        DataManager.getInstance().getPlaylistsAsync(true,null);
    }

    /**
     * Called when the fragment is no longer attached to its activity.
     * Cleans up the listener reference.
     */
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    /**
     * Called when the fragment is no longer visible to the user.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
    }

    // --- Initialization Helpers ---

    /**
     * Finds and assigns all necessary view elements from the layout.
     * @param view The root view of the fragment.
     */
    private void initializeViews(@NonNull View view) {
        searchBar = view.findViewById(R.id.searchBar);
        fabAddSong = view.findViewById(R.id.fabAddSong);
        scrollToTopButton = view.findViewById(R.id.scrollToTopButton);
        filterButton = view.findViewById(R.id.filterButton);
        recyclerView = view.findViewById(R.id.songRecyclerView);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
    }

    /**
     * Sets up the RecyclerView, its LayoutManager, and the LibraryAdapter.
     */
    private void setupRecyclerView() {
        // Use a vertical linear layout manager
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));

        // Create and set the adapter
        songAdapter = new LibraryAdapter(requireContext(), filteredSongList, createSongAdapterListener());
        recyclerView.setAdapter(songAdapter);

        // Scroll listener to show/hide button
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layoutManager != null) {
                    int firstVisiblePosition = layoutManager.findFirstVisibleItemPosition();

                    // more than 6 items scrolled down
                    if (firstVisiblePosition > 6) {
                        if(scrollToTopButton != null) scrollToTopButton.show();

                        // Reset the hide timer
                        handler.removeCallbacks(hideRunnable);
                        hideRunnable = () -> {
                            if (scrollToTopButton != null) scrollToTopButton.hide();
                        };
                        handler.postDelayed(hideRunnable, 2000);
                    } else {
                        if(scrollToTopButton != null) scrollToTopButton.hide();

                        // Also cancel any pending hide if we're back at top
                        handler.removeCallbacks(hideRunnable);
                    }
                }
            }
        });
    }

    /**
     * Creates and returns the listener implementation for the {@link LibraryAdapter}.
     * Handles actions performed on individual songs within the RecyclerView.
     * @return An instance of {@link LibraryAdapter.SongAdapterListener}.
     */
    private LibraryAdapter.SongAdapterListener createSongAdapterListener() {
        return new LibraryAdapter.SongAdapterListener() {
            @Override
            public void onSongDeleted(int pos) {
                if (filteredSongList.isEmpty() || pos < 0 || pos >= filteredSongList.size() || getContext() == null) return;

                Context context = getContext();
                Song song = filteredSongList.get(pos);

                // Delete the audio
                boolean result = FileUtils.deleteFileUri(context.getApplicationContext(), song.getAudioUri());
                if(result) {
                    filteredSongList.remove(pos);
                    songAdapter.notifyItemRemoved(pos);
                    songAdapter.notifyItemRangeChanged(pos, filteredSongList.size());
                    Toast.makeText(context, "Deleted: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                    if(listener != null) listener.requestPlaylistRefresh(true);
                }
                else {
                    Toast.makeText(context, "Failed to delete: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                }

                // If a song is deleted, it will also remove it from the full list
                fullSongList.remove(song);
            }

            @Override
            public void onSongClicked(int pos) {
                if(filteredSongList.isEmpty()) return;

                if (listener != null) {
                    listener.onLaunchMediaPlayer(filteredSongList, pos, 1);
                }
            }

            @Override
            public void onSongAddPlaylist(int pos) {
                if(filteredSongList.isEmpty() || pos < 0 || pos >= filteredSongList.size()) return;

                Log.d("LibraryFragment", "onSongAddPlaylist called");
                List<Song> selectedSongs = new ArrayList<>();
                selectedSongs.add(filteredSongList.get(pos));
                ShowPlaylistPickerDialog(selectedSongs);
            }

            @Override
            public void onSongCopy(int pos) {
                if (filteredSongList.isEmpty() || pos < 0 || pos >= filteredSongList.size() || getContext() == null) return;

                Context context = getContext();
                Song song = filteredSongList.get(pos);

                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Song Title", song.getTitle());
                clipboard.setPrimaryClip(clip);

                Toast.makeText(context, "Copied to clipboard.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSongShare(int pos) {
                if (filteredSongList.isEmpty() || pos < 0 || pos >= filteredSongList.size() || getContext() == null) return;

                Context context = getContext();
                Song song = filteredSongList.get(pos);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("audio/*");
                shareIntent.putExtra(Intent.EXTRA_STREAM, song.getAudioUri());
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                // Grant URI permission to all potential receivers
                List<ResolveInfo> resInfoList;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    resInfoList = context.getPackageManager().queryIntentActivities(shareIntent, PackageManager.ResolveInfoFlags.of(0));
                } else {
                    resInfoList = context.getPackageManager().queryIntentActivities(shareIntent, 0);
                }

                for (ResolveInfo resolveInfo : resInfoList) {
                    String packageName = resolveInfo.activityInfo.packageName;
                    context.grantUriPermission(packageName, song.getAudioUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }

                context.startActivity(Intent.createChooser(shareIntent, "Share song via"));
            }

            @Override
            public void activateLongPress() {
                showMultiSelectMenu();
            }

            @Override
            public void onToggleSelection(int pos) {
                updateMultiSelectCount();
            }
        };
    }

    /**
     * Sets up various listeners for UI interactions like button clicks, search input,
     * swipe-to-refresh, and touch events for hiding the keyboard.
     * @param view The root view of the fragment.
     */
    private void setupListeners(@NonNull View view) {
        View root = view.findViewById(R.id.libraryContent); // Get the main content area

        // Hide keyboard when touching outside the search bar
        root.setOnTouchListener((v, event) -> {
            if (searchBar.isFocused()) {
                hideKeyboard(searchBar);
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick(); // simulate a click for accessibility tools
            }
            return false; // Still let the event pass through
        });


        // Floating Action Button to add a new song (open download dialog)
        fabAddSong.setOnClickListener(v -> OpenDownloadDialog());

        // Floating Action Button to scroll to the top of the RecyclerView
        scrollToTopButton.setOnClickListener(v -> {
            if(recyclerView != null) recyclerView.scrollToPosition(0);
            if(scrollToTopButton != null) scrollToTopButton.hide();
        });

        // SwipeRefreshLayout to refresh the song list
        swipeRefreshLayout.setOnRefreshListener(this::refreshSongList); // Use method reference

        // Filter button to open sorting options
        filterButton.setOnClickListener(v -> OpenFilterDialog());

        // Search bar text changes listener
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isAdded() || searchBar == null || searchBar.getText() == null) return;
                // Apply the filter as the user types
                applySearchFilter(searchBar.getText().toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Hide keyboard
                hideKeyboard(searchBar);
                return true;
            }
            return false;
        });
    }

    /**
     * Configures the handling of the back button press.
     * If in multi-select mode, it cancels the mode; otherwise, it performs the default back action.
     */
    private void setupBackPressedHandler() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (songAdapter != null && songAdapter.isMultiSelectMode()) {
                    // If yes, exit multi-select mode
                    hideMultiSelectMenu();
                } else {
                    // Otherwise, disable this callback and let the default back press happen
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // --- Multi-Select Menu Handling ---

    /**
     * Shows the multi-select action bar at the bottom of the screen.
     * Inflates the menu layout, sets up button listeners for multi-select actions
     * (Delete, Play, Add to Playlist, Back).
     */
    private void showMultiSelectMenu() {
        if (multiSelectMenuView != null) return;

        // Ensure fragment view is available and fragment is added
        View rootView = getView();
        if (rootView == null || !isAdded()) return;

        FrameLayout container = rootView.findViewById(R.id.multiSelectBarContainer);
        if (container == null) return;

        // Inflate the menu layout
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        multiSelectMenuView = inflater.inflate(R.layout.library_multiselect_menu, container, false);
        multiSelectTextView = multiSelectMenuView.findViewById(R.id.selectedCountText);

        container.addView(multiSelectMenuView);
        container.setVisibility(View.VISIBLE);

        updateMultiSelectCount();

        // --- Setup Listeners for Multi-Select Menu Buttons ---

        // Back button: Hides the multi-select menu
        multiSelectMenuView.findViewById(R.id.btnBack).setOnClickListener(v -> {
            if(songAdapter == null || multiSelectMenuView == null) return;

            hideMultiSelectMenu();
        });

        // Delete button: Opens confirmation dialog for selected songs
        multiSelectMenuView.findViewById(R.id.btnDelete).setOnClickListener(v -> {
            if(songAdapter == null || multiSelectMenuView == null) return;

            Set<Song> selected = songAdapter.getSelectedSongs();
            int count = selected.size();
            if (count == 0) return;

            OpenDeleteConfirmation(selected);
        });

        // Play button: Plays the selected songs
        multiSelectMenuView.findViewById(R.id.btnPlay).setOnClickListener(v -> {
            if(songAdapter == null || multiSelectMenuView == null) return;

            Set<Song> selected = songAdapter.getSelectedSongs();
            int count = selected.size();
            if (count == 0) return;

            if (listener != null) {
                List<Song> songList = new ArrayList<>(selected);
                listener.onLaunchMediaPlayer(songList, 0, 2);
            }
            hideMultiSelectMenu();
        });

        // Add button: Opens playlist picker for selected songs
        multiSelectMenuView.findViewById(R.id.btnAdd).setOnClickListener(v -> {
            if(songAdapter == null || multiSelectMenuView == null) return;

            List<Song> selectedSongs = new ArrayList<>(songAdapter.getSelectedSongs());
            if(selectedSongs.isEmpty()) return;

            ShowPlaylistPickerDialog(selectedSongs);
        });
    }

    /**
     * Hides the multi-select action bar.
     * Deactivates multi-select mode in the adapter and removes the menu view.
     */
    private void hideMultiSelectMenu() {
        if(songAdapter != null) {
            songAdapter.setMultiSelectMode(false);
        }

        View rootView = getView();
        if (rootView == null || !isAdded()) return;

        FrameLayout container = rootView.findViewById(R.id.multiSelectBarContainer);
        if (container == null) return;

        // Remove the menu view and hide the container
        container.removeAllViews();
        container.setVisibility(View.GONE);
        multiSelectMenuView = null;
        multiSelectTextView = null;
    }

    /**
     * Updates the text view in the multi-select menu to show the current number of selected songs.
     */
    private void updateMultiSelectCount() {
        if (multiSelectMenuView == null || songAdapter == null ||
                !songAdapter.isMultiSelectMode() || multiSelectTextView == null) return;

        int count = songAdapter.getSelectedSongs().size();
        multiSelectTextView.setText(count + " selected");
    }

    // --- Dialogs and Popups ---

    /**
     * Displays a dialog allowing the user to select one or more playlists
     * to add the specified songs to.
     * @param selectedSongs The list of songs to be added to playlists.
     */
    public void ShowPlaylistPickerDialog(List<Song> selectedSongs) {
        if (!isAdded() || getContext() == null) return;

        Context context = getContext();
        List<String> allPlaylists = DataManager.getInstance().getAllPlaylistNames();
        if(allPlaylists == null || allPlaylists.isEmpty() ||
                selectedSongs == null || selectedSongs.isEmpty()) return;

        // Inflate the dialog view
        View dialogView = LayoutInflater.from(context).inflate(R.layout.playlist_picker_popup, null);
        RecyclerView recyclerView = dialogView.findViewById(R.id.playlistRecyclerView);
        Button btnConfirm = dialogView.findViewById(R.id.btnConfirm);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        PlaylistPickerAdapter adapter = new PlaylistPickerAdapter(allPlaylists);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);

        // Create the dialog
        AlertDialog dialog = new AlertDialog.Builder(context, R.style.CustomDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        // Confirm button: Add songs to selected playlists
        btnConfirm.setOnClickListener(v -> {
            Set<String> selected = adapter.getSelectedPlaylists();
            if (selected == null || selected.isEmpty()) {
                if(dialog.isShowing()) dialog.dismiss();
                return;
            }

            // Add the songs to the playlists
            DataManager.getInstance().addSongsToPlaylistsAsync(selectedSongs, new ArrayList<>(selected), success -> {
                if(listener != null) listener.requestPlaylistRefresh(false);
                if (!isAdded()) return;

                if (success) {
                    Toast.makeText(context, "Song(s) added to playlists", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Failed to add song(s) to playlists", Toast.LENGTH_SHORT).show();
                }
            });
            if(dialog.isShowing()) dialog.dismiss();
            hideMultiSelectMenu();
        });

        // Cancel button: Just dismiss the dialog
        btnCancel.setOnClickListener(v -> {
            if(dialog.isShowing()) dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * Displays a confirmation dialog before deleting multiple selected songs.
     * @param songsToDelete The set of {@link Song} objects selected for deletion.
     */
    private void OpenDeleteConfirmation(Set<Song> songsToDelete) {
        if (!isAdded() || getContext() == null) return;

        // Inflate the confirmation dialog layout
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null);
        TextView textDisplay = dialogView.findViewById(R.id.textDisplay);
        Button btnDelete = dialogView.findViewById(R.id.btnDelete);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        textDisplay.setText("Are you sure you want to delete " + songsToDelete.size() + " song" + (songsToDelete.size() > 1 ? "s" : "") + "?");

        // Create the AlertDialog
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        // Delete button listener
        btnDelete.setOnClickListener(v -> {
            for (Song song : songsToDelete) {
                FileUtils.deleteFileUri(requireContext().getApplicationContext(), song.getAudioUri());
            }
            refreshSongList();
            hideMultiSelectMenu();
            if(dialog.isShowing()) dialog.dismiss();
            if(listener != null) listener.requestPlaylistRefresh(true);
        });

        // Cancel button listener
        btnCancel.setOnClickListener(v -> {
            if(dialog.isShowing()) dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * Displays a popup menu anchored to the filter button, allowing the user to select a sort order.
     */
    private void OpenFilterDialog() {
        if (!isAdded() || getContext() == null) return;

        View popupView = LayoutInflater.from(requireContext()).inflate(R.layout.sort_options_popup, null);
        PopupWindow popupWindow = new PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
        );
        popupWindow.setElevation(12f);
        popupWindow.setOutsideTouchable(true);

        // Array of indicator dots and their matching sort IDs
        final View[] dots = {
                popupView.findViewById(R.id.dot_alpha),
                popupView.findViewById(R.id.dot_newest),
                popupView.findViewById(R.id.dot_oldest)
        };
        // Array of sort option buttons and their IDs
        final int[] optionIds = {
                R.id.sort_alpha,
                R.id.sort_newest,
                R.id.sort_oldest
        };

        // Hide all dots, then show the selected one
        for (int i = 0; i < dots.length; i++) {
            dots[i].setVisibility(i == selectedSortId ? View.VISIBLE : View.GONE);
        }

        // Set up click listeners in a loop
        for (int i = 0; i < optionIds.length; i++) {
            int sortId = i;
            popupView.findViewById(optionIds[i]).setOnClickListener(v -> {
                if (selectedSortId != sortId) {
                    selectedSortId = sortId;
                    refreshSongList();
                    DataManager.Settings.SetSortOrder(sortId);
                }
                if (popupWindow.isShowing()) popupWindow.dismiss();
            });
        }

        popupWindow.showAsDropDown(filterButton, 0, 0);
    }

    /**
     * Displays a dialog for the user to input a URL to download a song.
     */
    private void OpenDownloadDialog() {
        if (!isAdded() || getContext() == null) return;

        if (DataManager.Settings.GetAudioDirectory() == null) {
            Toast.makeText(requireContext(), "Set your audio directory in 'Settings'", Toast.LENGTH_SHORT).show();
            return;
        }

        // Inflate the custom dialog layout
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_download_popup, null);
        EditText urlInput = dialogView.findViewById(R.id.urlInput);
        Button btnDownload = dialogView.findViewById(R.id.btnDownload);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        ImageButton pasteButton = dialogView.findViewById(R.id.pasteButton);

        // Create the AlertDialog
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        // Download button listener
        btnDownload.setOnClickListener(v -> {
            if (DataManager.Settings.GetAudioDirectory() == null) {
                Toast.makeText(requireContext(), "Set your audio directory in 'Settings'", Toast.LENGTH_SHORT).show();
                return;
            }

            // If URL is provided and listener exists, request download via the activity
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) {
                if(listener != null) listener.onDownloadRequested(url);
                if(dialog.isShowing()) dialog.dismiss();
            } else {
                Toast.makeText(requireContext(), "URL cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });

        // Cancel button listener
        btnCancel.setOnClickListener(v -> {
            if(dialog.isShowing()) dialog.dismiss();
        });

        // Paste button listener
        pasteButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (!clipboard.hasPrimaryClip()) {
                return;
            }

            // Check if clipboard has content and it's text
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0 || clipData.getItemAt(0) == null) {
                return;
            }

            CharSequence text = clipData.getItemAt(0).getText();
            if (text != null && !text.toString().isEmpty() && urlInput != null) {
                urlInput.setText(text.toString().trim());
                urlInput.setSelection(urlInput.getText().length());
            }
        });

        // Listener for URL input
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Hide keyboard
                hideKeyboard(urlInput);
                return true;
            }
            return false;
        });

        dialog.show();
    }

    // --- Utility Methods ---

    /**
     * Hides the soft keyboard associated with the given view.
     * @param view The view that currently has focus (e.g., an EditText).
     */
    private void hideKeyboard(View view) {
        if (!isAdded() || view == null) return;

        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        view.clearFocus();
    }

    // --- Data Handling (Refresh, Sort, Filter) ---

    /**
     * Sorts the {@link #fullSongList} based on the {@link #selectedSortId}.
     * Does not update the UI directly; {@link #applySearchFilter} should be called afterwards
     * to update the displayed list ({@link #filteredSongList}) and notify the adapter.
     */
    private void applySort() {
        switch (selectedSortId) {
            case 0: fullSongList.sort(Song.ALPHABETICAL_COMPARATOR); break;
            case 1: fullSongList.sort(Song.NEWEST_COMPARATOR); break;
            case 2: fullSongList.sort(Song.OLDEST_COMPARATOR); break;
            default: fullSongList.sort(Song.ALPHABETICAL_COMPARATOR); break;
        }
    }

    /**
     * Filters the {@link #fullSongList} based on the provided query string and populates
     * the {@link #filteredSongList} with the results.
     * Notifies the {@link #songAdapter} that the data set has changed.
     * @param query The search query string. If null or empty, displays all songs.
     */
    private void applySearchFilter(String query) {
        filteredSongList.clear();
        if (query == null || query.trim().isEmpty()) {
            // If query is empty, show all songs (from the sorted full list)
            filteredSongList.addAll(fullSongList);
        } else {
            // Otherwise, do string matching
            Collator collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.PRIMARY);

            for (Song song : fullSongList) {
                String title = song.getTitle();
                if (collator.compare(title, query) == 0 || title.toLowerCase().contains(query.toLowerCase())) {
                    filteredSongList.add(song);
                }
            }
        }
        if (songAdapter != null) {
            songAdapter.notifyDataSetChanged();
        }

    }

    /**
     * Refreshes the list of songs by rescanning the audio directory.
     * Shows the swipe-to-refresh indicator, fetches songs using {@link DataManager},
     * applies the current sort order and search filter, and updates the RecyclerView.
     */
    public void refreshSongList() {
        if (swipeRefreshLayout != null && !swipeRefreshLayout.isRefreshing()) {
            swipeRefreshLayout.setRefreshing(true);
        }

        Uri audioDir = DataManager.Settings.GetAudioDirectory();
        if (audioDir == null || getContext() == null) {
            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // Asynchronously fetch songs from the directory
        DataManager.getInstance().getSongsInDirectoryAsync(updatedSongs -> {
            if (!isAdded()) return;

            fullSongList.clear();
            fullSongList.addAll(updatedSongs);

            applySort();
            if (searchBar != null && searchBar.getText() != null) {
                String query = searchBar.getText().toString();
                applySearchFilter(query);
            }

            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        });
    }

}