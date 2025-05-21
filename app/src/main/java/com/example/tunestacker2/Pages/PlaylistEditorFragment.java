package com.example.tunestacker2.Pages;


import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tunestacker2.Data.DataManager;
import com.example.tunestacker2.Data.FileUtils;
import com.example.tunestacker2.MusicPlayer.Playlist;
import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.MusicPlayer.ThumbnailLoader;
import com.example.tunestacker2.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * A Fragment responsible for displaying and editing the contents of a specific Playlist.
 * Allows users to reorder songs, delete songs, play the playlist, rename the playlist,
 * and delete the entire playlist.
 */
public class PlaylistEditorFragment extends Fragment {
    /**
     * Interface definition for a callback to be invoked when certain actions
     * are performed within the PlaylistEditorFragment.
     */
    public interface PlaylistEditorFragmentListener {
        /**
         * Called when the user requests to play songs from the playlist.
         *
         * @param songs The list of songs to play (could be shuffled).
         * @param pos The starting position within the list.
         * @param repeatState The initial repeat state for the media player.
         */
        void onLaunchMediaPlayer(List<Song> songs, int pos, int repeatState);

        /**
         * Called when the playlist data might have changed (e.g., renamed, deleted)
         * and the hosting view (like the main playlist list) should refresh itself.
         */
        void requestPlaylistRefresh(boolean freshFetch);
    }

    // --- Constants ---
    // Argument key for passing the Playlist object to the fragment.
    private static final String ARG_PLAYLIST = "playlist_arg";
    // Log tag for debugging purposes.
    private static final String TAG = "PlaylistEditorFragment";

    // --- Data ---
    // The playlist being edited
    private Playlist playlist;
    private List<Song> songList;

    // --- UI Elements ---
    private View editorContainer;
    private TextView playlistTitle;
    private TextView songCount;
    private RecyclerView playlistRecyclerView;
    private PlaylistEditorAdapter adapter;
    private ImageView playlistThumbnail;
    private Button btnPlayAll;
    private ImageButton btnOptions;
    private ImageButton btnBack;
    private Button btnShuffle;
    private FloatingActionButton scrollToTopButton;

    // --- Communication ---
    // Listener interface for communicating events back to the hosting Activity.
    private PlaylistEditorFragmentListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;

    // --- State ---
    private boolean isClosing = false;
    private boolean isInvalidState = false;
    private boolean changesMadeToPlaylist = false;


    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public PlaylistEditorFragment() {
        // Required empty public constructor
    }

    /**
     * Factory method to create a new instance of this fragment
     * using the provided Playlist.
     *
     * @param playlist The Playlist to edit.
     * @return A new instance of fragment PlaylistEditorFragment.
     */
    public static PlaylistEditorFragment newInstance(Playlist playlist) {
        PlaylistEditorFragment fragment = new PlaylistEditorFragment();
        Bundle args = new Bundle();

        args.putParcelable(PlaylistEditorFragment.ARG_PLAYLIST, playlist);
        fragment.setArguments(args);
        return fragment;
    }

    // --- Lifecycle Methods ---

    /**
     * Called when the fragment is first attached to its context.
     * Ensures that the hosting Activity implements the required listener interface.
     * @param context The application context.
     */
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof PlaylistEditorFragmentListener) {
            listener = (PlaylistEditorFragmentListener) context;
        } else {
            throw new RuntimeException(context + " must implement OnMediaPlayerLaunchListener");
        }
    }

    /**
     * Called to do initial creation of the fragment.
     * Retrieves the Playlist object passed as an argument.
     * @param savedInstanceState If the fragment is being re-created from a previous saved state, this is the state.
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            playlist = getArguments().getParcelable(ARG_PLAYLIST);
            if (playlist != null) {
                songList = new ArrayList<>(playlist.getSongs()); // Create a copy
            }
            else {
                // Handle case where playlist is null
                Log.e(TAG, "Playlist argument was null in onCreate.");
                isInvalidState = true;
            }
        }
        else {
            // Handle case where fragment is created without arguments by closing
            Log.e(TAG, "Playlist argument was null in onCreate.");
            isInvalidState = true;
        }
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     * @param inflater The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container If non-null, this is the parent view that the fragment's UI should be attached to.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state as given here.
     * @return Return the View for the fragment's UI, or null.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist_editor, container, false);
    }

    /**
     * Called immediately after {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}
     * has returned, but before any saved state has been restored in to the view.
     * This is where view initialization, RecyclerView setup, and listener bindings occur.
     * @param view The View returned by {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)}.
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (isInvalidState) {
            view.post(() -> requireActivity().getSupportFragmentManager().popBackStack());
            return;
        }

        // Initialize UI elements
        initializeViews(view);

        // Setup recycler view
        setupRecyclerView();

        // Setup button listeners
        setupButtonClickListeners();

        // Initial population of UI text/images
        refreshPlaylistView();

        // Handle system back button press to trigger custom animation
        setupBackButtonOverride();

        // Animate the fragment into view
        animateFragmentIn();
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

    // --- Initialization Methods ---

    /**
     * Finds and assigns all the necessary view elements from the layout.
     * @param view The fragment's root view.
     */
    private void initializeViews(@NonNull View view) {
        // Find the container in the *Activity* layout (assumed)
        editorContainer = requireActivity().findViewById(R.id.playlistEditorContainer);
        if (editorContainer == null) {
            Log.e(TAG, "Could not find editorContainer (R.id.playlistEditorContainer) in Activity layout!");
        }

        // Find views within the fragment's layout
        playlistTitle = view.findViewById(R.id.playlistTitle);
        songCount = view.findViewById(R.id.songCount);
        playlistThumbnail = view.findViewById(R.id.playlistThumbnail);
        playlistRecyclerView = view.findViewById(R.id.playlistRecyclerView);
        btnPlayAll = view.findViewById(R.id.btnPlayAll);
        btnShuffle = view.findViewById(R.id.btnShuffle);
        btnOptions = view.findViewById(R.id.btnOptions);
        btnBack = view.findViewById(R.id.btnBack);
        scrollToTopButton = view.findViewById(R.id.scrollToTopButton);
    }

    /**
     * Sets up the RecyclerView, its adapter, layout manager, and the ItemTouchHelper
     * for handling drag-and-drop and swipe actions.
     */
    private void setupRecyclerView() {
        if (getContext() == null || songList == null) {
            Log.e(TAG, "Context or songList is null in setupRecyclerView.");
            return;
        }

        // Configure the layout manager for the RecyclerView
        playlistRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new PlaylistEditorAdapter(requireContext(), songList, new PlaylistEditorAdapter.PlaylistEditorAdapterListener() {
            @Override
            public void onSongDeleted(int pos) {
                if(songList == null || songList.isEmpty() || pos < 0 || pos >= songList.size()) return;

                final Song songToRemove = songList.get(pos);
                DataManager.getInstance().removeSongInPlaylistAsync(playlist.getTitle(), songToRemove, success -> {
                    if(!isAdded()) return;

                    // If the operation was unsuccessful
                    if(!success) {
                        Toast.makeText(requireContext(), "Failed to remove song.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // If the operation was successful, remove the song from the list and update the UI
                    if(songList != null && !songList.isEmpty() && pos >= 0 && pos < songList.size()) {
                        songList.remove(pos);
                        adapter.notifyItemRemoved(pos);
                    }

                    refreshPlaylistView();
                    // if(listener != null) listener.requestPlaylistRefresh();
                    changesMadeToPlaylist = true;
                });
            }

            @Override
            public void onSongClicked(int pos) {
                if(songList == null || songList.isEmpty() || pos < 0 || pos >= songList.size()) return;

                if(listener != null) {
                    listener.onLaunchMediaPlayer(songList, pos, 2);
                }
            }

            @Override
            public void onSongMoveUp(int pos) {
                if(songList == null || songList.isEmpty() || pos <= 0 || pos >= songList.size()) return;

                // Move the song to the very top
                Song song = songList.remove(pos);
                songList.add(0, song);
                adapter.notifyItemMoved(pos, 0);

                // Persist the changed order after the drag operation is complete
                DataManager.getInstance().updateSongsInPlaylistAsync(playlist.getTitle(), songList, success -> {
                    if(!isAdded()) return;
                    changesMadeToPlaylist = true;
                    refreshPlaylistView();
                });
            }

            @Override
            public void onSongMoveDown(int pos) {
                if(songList == null || songList.isEmpty() || pos < 0 || pos >= songList.size() - 1) return;

                // Move the song to the very bottom
                Song song = songList.remove(pos);
                songList.add(song);
                adapter.notifyItemMoved(pos, songList.size() - 1);

                // Persist the changed order after the drag operation is complete
                DataManager.getInstance().updateSongsInPlaylistAsync(playlist.getTitle(), songList, success -> {
                    if(!isAdded()) return;

                    if(pos == 0) {
                        // Micro-optimization to only refresh when it would actually change the display
                        changesMadeToPlaylist = true;
                        refreshPlaylistView();
                    }
                });
            }
        });
        playlistRecyclerView.setAdapter(adapter);

        // Scroll listener to show/hide button
        playlistRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
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

        // Setup drag-and-drop functionality
        setupItemTouchHelper();
    }

    /**
     * Configures and attaches the ItemTouchHelper to the RecyclerView for drag-and-drop reordering.
     */
    private void setupItemTouchHelper() {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, // Enable dragging up and down
                0) { // Disable swiping

            /**
             * Called when an item is dragged and moved over another item.
             * @param recyclerView The RecyclerView instance.
             * @param viewHolder The ViewHolder being dragged.
             * @param target The ViewHolder over which the dragged item is currently hovering.
             * @return True if the move was handled, false otherwise.
             */
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                // Handle drag and drop
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();

                // Swap the items in the local list
                if (fromPos < 0 || toPos < 0 || fromPos >= songList.size() || toPos >= songList.size()) return false;

                // Update the list
                Collections.swap(songList, fromPos, toPos);
                adapter.notifyItemMoved(fromPos, toPos);

                return true;
            }

            /**
             * Called when the user interaction with an element is over and it also completed its animation.
             * This is where we persist the reordered list.
             * @param recyclerView The RecyclerView instance.
             * @param viewHolder The ViewHolder that was interacted with.
             */
            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // Persist the changed order after the drag operation is complete
                DataManager.getInstance().updateSongsInPlaylistAsync(playlist.getTitle(), songList, success -> {
                    if(!isAdded()) return;
                    changesMadeToPlaylist = true;
                    refreshPlaylistView();
                });
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public boolean isLongPressDragEnabled() {
                return true; // Enable drag on long press
            }
        };

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(playlistRecyclerView);
    }

    /**
     * Sets up the OnClickListeners for the various buttons in the fragment.
     */
    private void setupButtonClickListeners() {
        // Back button closes the fragment with animation
        btnBack.setOnClickListener(v -> closeWithAnimation());

        // Play All button starts playback from the beginning
        btnPlayAll.setOnClickListener(v -> {
            if (listener != null && songList != null && !songList.isEmpty()) {
                listener.onLaunchMediaPlayer(songList, 0, 2);
            }
        });

        // Shuffle button shuffles the list and starts playback
        btnShuffle.setOnClickListener(v -> {
            if (listener != null && songList != null && !songList.isEmpty()) {
                List<Song> shuffledList = new ArrayList<>(songList);
                Collections.shuffle(shuffledList);
                listener.onLaunchMediaPlayer(shuffledList, 0, 2);
            }
        });

        // Options menu click event
        btnOptions.setOnClickListener(v -> {
            if (getContext() == null) return;

            View popupView = LayoutInflater.from(requireContext()).inflate(R.layout.playlist_editor_options_popup, null);
            PopupWindow popupWindow = new PopupWindow(
                    popupView,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    true
            );
            popupWindow.setElevation(12f);
            popupWindow.setOutsideTouchable(true);

            // Handle each options button click
            popupView.findViewById(R.id.option_rename).setOnClickListener(v2 -> {
                if(popupWindow.isShowing()) popupWindow.dismiss();
                OpenRenameDialog();
            });
            popupView.findViewById(R.id.option_delete).setOnClickListener(v2 -> {
                if (popupWindow.isShowing()) popupWindow.dismiss();
                OpenDeleteConfirmation();
            });

            popupWindow.showAsDropDown(btnOptions, 0, 0);
        });

        // Floating Action Button to scroll to the top of the playlistRecyclerView
        scrollToTopButton.setOnClickListener(v -> {
            if(playlistRecyclerView != null) playlistRecyclerView.scrollToPosition(0);
            if(scrollToTopButton != null) scrollToTopButton.hide();
        });
    }

    /**
     * Overrides the default back button behavior to close the fragment with animation.
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

    // --- Action Handlers ---

    /**
     * Opens a dialog prompting the user to enter a new name for the playlist.
     * Handles input validation and calls DataManager to perform the rename operation.
     */
    private void OpenRenameDialog() {
        if (!isAdded() || getContext() == null) return;

        // Inflate the custom dialog layout
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_rename_popup, null);
        EditText textInput = dialogView.findViewById(R.id.textInput);
        Button btnConfirm = dialogView.findViewById(R.id.btnConfirm);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        // Pre-fill the input with the current playlist name
        textInput.setText(playlist.getTitle());
        textInput.setSelection(textInput.getText().length());

        // Create the AlertDialog
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        // Add a TextWatcher to sanitize input in real-time
        textInput.addTextChangedListener(new TextWatcher() {
            private boolean isUpdating = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                // Avoid recursive calls when setText is called within this method
                if (isUpdating) return;

                String original = s.toString();
                String sanitized = FileUtils.sanitizeFilename(original);

                // If sanitation changed the text, update the EditText
                if (!sanitized.equals(original.trim())) {
                    isUpdating = true;
                    textInput.setText(sanitized);
                    textInput.setSelection(Math.min(sanitized.length(), textInput.getText().length()));
                    isUpdating = false;
                }
            }
        });

        // Listener for name input
        textInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Hide keyboard
                hideKeyboard(textInput);
                return true;
            }
            return false;
        });

        // Set click listener for the Confirm button
        btnConfirm.setOnClickListener(v -> {
            String newName = textInput.getText().toString().trim();
            newName = FileUtils.sanitizeFilename(newName);

            // Check if the new name is empty
            if (newName.isEmpty()) {
                Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            // If there is a valid name, rename the playlist
            String finalNewName = newName;
            DataManager.getInstance().updateNamePlaylistAsync(playlist.getTitle(), newName, error -> {
                if(!isAdded()) return;

                if(error != null) {
                    // Display error message from DataManager (e.g., name already exists)
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                } else {
                    // Rename successful
                    // if(listener != null) listener.requestPlaylistRefresh();
                    changesMadeToPlaylist = true;
                    playlist.setTitle(finalNewName);
                    refreshPlaylistView();
                }
            });

            if(dialog.isShowing()) dialog.dismiss();
        });

        // Set click listener for the Cancel button
        btnCancel.setOnClickListener(v -> {
            if(dialog.isShowing()) dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * Opens a confirmation dialog before deleting the entire playlist.
     * Calls DataManager to perform the deletion if confirmed.
     */
    private void OpenDeleteConfirmation() {
        if (!isAdded() || getContext() == null) return;

        // Inflate the custom dialog layout
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_delete, null);
        TextView textDisplay = dialogView.findViewById(R.id.textDisplay);
        Button btnDelete = dialogView.findViewById(R.id.btnDelete);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        // Set the confirmation message, including the playlist title
        textDisplay.setText("Are you sure you want to delete " + playlist.getTitle() + "?");

        // Create the AlertDialog
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.CustomDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        // Set click listener for the Delete button
        btnDelete.setOnClickListener(v -> {
            DataManager.getInstance().removePlaylistAsync(playlist.getTitle(), success ->{
                if(!isAdded()) return;

                if(success) {
                    // Deletion successful
                    // if(listener != null) listener.requestPlaylistRefresh();
                    changesMadeToPlaylist = true;
                    closeWithAnimation();
                } else {
                    // Deletion failed
                    Toast.makeText(requireContext(), "Failed to remove playlist.", Toast.LENGTH_SHORT).show();
                }
            });
            if(dialog.isShowing()) dialog.dismiss();
        });

        // Set click listener for the Cancel button
        btnCancel.setOnClickListener(v -> {
            if(dialog.isShowing()) dialog.dismiss();
        });

        dialog.show();
    }

    // --- UI Update and Animation ---

    /**
     * Updates the UI elements (title, song count, thumbnail) based on the current
     * state of the `playlist` and `songList`.
     */
    private void refreshPlaylistView() {
        if (playlist == null || songList == null) return;

        playlistTitle.setText(playlist.getTitle());
        songCount.setText(songList.size() + " songs");

        // Ensures that the thumbnail is not null before attempting to load
        if(songList.isEmpty()) {
            playlistThumbnail.setImageResource(ThumbnailLoader.DEFAULT_THUMBNAIL);
            return;
        }

        // Update the playlist thumbnail based on the first song
        playlistThumbnail.setImageBitmap(ThumbnailLoader.loadThumbnailNonNullSync(songList.get(0), requireContext().getApplicationContext()));
        ThumbnailLoader.loadLargeThumbnailAsync(songList.get(0), requireContext().getApplicationContext(), bitmap -> {
            if (isAdded() && playlistThumbnail != null) {
                playlistThumbnail.setImageBitmap(bitmap);
            }
        });
    }

    /**
     * Animates the player container sliding up into view.
     */
    private void animateFragmentIn() {
        // Ensure editorContainer is not null before proceeding.
        if (editorContainer == null) {
            Log.e("PlaylistEditorFragment", "editorContainer is null in animateFragmentIn!");
            return;
        }

        // Calculate screen height for the starting position of the animation.
        int screenHeight = getScreenHeight();

        // Start slightly off-screen (e.g., 20% down) and invisible/gone
        editorContainer.setTranslationY(screenHeight * 0.2f); // Start position
        editorContainer.setVisibility(View.VISIBLE); // Make it visible before animation starts

        // Animate translationY to 0 (its original position).
        editorContainer.animate()
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

        // Refresh playlist view when going back
        if(listener != null && changesMadeToPlaylist) listener.requestPlaylistRefresh(false);

        // Fallback if editorContainer is null
        if (editorContainer == null && isAdded()) {
            requireActivity().getSupportFragmentManager().popBackStack();
            return;
        }

        editorContainer.animate()
                .translationY(editorContainer.getHeight())
                .setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (editorContainer != null) editorContainer.setVisibility(View.GONE);
                    isClosing = false; // Reset flag just in case fragment is reused
                    if (isAdded()) {
                        requireActivity().getSupportFragmentManager().popBackStack();
                    }
                })
                .start();
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

