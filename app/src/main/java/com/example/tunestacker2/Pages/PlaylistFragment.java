package com.example.tunestacker2.Pages;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.example.tunestacker2.Data.DataManager;
import com.example.tunestacker2.Data.FileUtils;
import com.example.tunestacker2.MusicPlayer.Playlist;
import com.example.tunestacker2.R;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * A Fragment that displays a list of playlists and allows users to create new ones.
 * It interacts with the hosting Activity via the {@link PlaylistFragmentListener} interface.
 */
public class PlaylistFragment extends Fragment {

    /**
     * Interface for communication with the hosting Activity.
     * Allows the fragment to request launching the playlist editor.
     */
    public interface PlaylistFragmentListener {
        /**
         * Called when a playlist item is clicked, requesting the editor to be launched.
         * @param playlist The playlist that was clicked.
         */
        void onLaunchPlaylistEditor(Playlist playlist);
    }

    // --- Constants ---
    private static final int GRID_SPAN_COUNT = 2; // Number of columns in the playlist grid

    // --- UI Elements ---
    private SwipeRefreshLayout swipeRefreshLayout;
    private EditText editPlaylistName;
    private Button btnAddPlaylist;
    private RecyclerView recyclerPlaylists;

    // --- Adapters and Data ---
    private PlaylistAdapter playlistAdapter;
    private List<Playlist> playlistList = new ArrayList<>();

    // --- Listener ---
    private PlaylistFragmentListener listener;


    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public PlaylistFragment() {
        // Required empty public constructor
    }

    /**
     * Called when the fragment is first attached to its context.
     * Ensures the hosting Activity implements the {@link PlaylistFragmentListener}.
     * @param context The application context.
     */
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof PlaylistFragmentListener) {
            listener = (PlaylistFragmentListener) context;
        } else {
            throw new RuntimeException(context + " must implement PlaylistFragmentListener");
        }
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     * This is where UI elements are initialized and listeners are set up.
     * @param inflater The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container If non-null, this is the parent view that the fragment's UI should be attached to.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state as given here.
     * @return Return the View for the fragment's UI, or null.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }


    /**
     * Called when the view of the fragment is being created
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Initialize UI components
        initializeViews(view);

        // Setup the RecyclerView to display playlists
        setupRecyclerView();

        // Setup input validation for the playlist name EditText
        setupPlaylistNameInputValidation();

        // Setup the swipe-to-refresh layout
        swipeRefreshLayout.setOnRefreshListener(this::refreshPlaylistList);

        // Perform an initial refresh of the playlist list
        refreshPlaylistList();
    }

    /**
     * Called when the fragment is being detached from its activity.
     * Clears the listener reference to avoid memory leaks.
     */
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    // --- Initialization Methods ---

    /**
     * Finds and assigns the UI elements from the inflated view.
     * @param view The root view of the fragment's layout.
     */
    private void initializeViews(@NonNull View view) {
        editPlaylistName = view.findViewById(R.id.editPlaylistName);
        btnAddPlaylist = view.findViewById(R.id.btnAddPlaylist);
        recyclerPlaylists = view.findViewById(R.id.recyclerPlaylists);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);

        btnAddPlaylist.setOnClickListener(v -> addPlaylist());
        editPlaylistName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Hide keyboard
                hideKeyboard(editPlaylistName);
                return true;
            }
            return false;
        });
    }

    /**
     * Sets up the RecyclerView, its LayoutManager, and its Adapter.
     */
    private void setupRecyclerView() {
        // Use a GridLayoutManager to display items in a grid
        recyclerPlaylists.setLayoutManager(new GridLayoutManager(getContext(), GRID_SPAN_COUNT));

        // Create and set the adapter for the RecyclerView
        playlistAdapter = new PlaylistAdapter(requireContext(), playlistList, new PlaylistAdapter.PlaylistAdapterListener() {
            @Override
            public void onPlaylistClicked(int pos) {
                // Handle playlist item clicks (If its the first one, don't bother updating)
                if (pos > 0 && pos < playlistList.size()) {
                    Playlist playlist = playlistList.get(pos);
                    if (listener != null) listener.onLaunchPlaylistEditor(playlist);

                    // Update last played time for the playlist
                    DataManager.getInstance().updateTimePlaylistAsync(playlist.getTitle(), success -> {
                        // Do nothing here
                    });
                }
            }
        });
        recyclerPlaylists.setAdapter(playlistAdapter);
    }

    /**
     * Sets up a TextWatcher on the playlist name EditText to sanitize the input
     * in real-time, removing characters invalid for filenames.
     */
    private void setupPlaylistNameInputValidation() {
        editPlaylistName.addTextChangedListener(new TextWatcher() {
            private boolean isUpdating = false; // Flag to prevent infinite loops during text replacement

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                // Avoid recursion if we are already updating the text
                if (isUpdating) return;

                String original = s.toString();
                String sanitized = FileUtils.sanitizeFilename(original);

                // If sanitization changed the string (and it wasn't just trailing whitespace)
                if (!sanitized.equals(original.trim())) {
                    isUpdating = true; // Set flag to prevent recursion
                    editPlaylistName.setText(sanitized);
                    // Move the cursor to the end, ensuring it doesn't go out of bounds
                    editPlaylistName.setSelection(Math.min(sanitized.length(), editPlaylistName.getText().length()));
                    isUpdating = false; // Reset flag
                }
            }
        });
    }

    // --- Core Logic Methods ---

    /**
     * Handles the action of adding a new playlist.
     * Validates the input, calls the DataManager to create the playlist asynchronously,
     * and updates the UI upon completion or error.
     */
    private void addPlaylist() {
        Context context = getContext();
        if (DataManager.Settings.GetAudioDirectory() != null && context != null) {
            // Check if playlist name is valid
            String playlistName = editPlaylistName.getText().toString();
            playlistName = FileUtils.sanitizeFilename(playlistName);
            if (TextUtils.isEmpty(playlistName)) {
                Toast.makeText(context, "Please enter a playlist name", Toast.LENGTH_SHORT).show();
                return;
            }
            btnAddPlaylist.setEnabled(false); // Disable button to prevent multiple taps

            // Call DataManager to create the new playlist asynchronously
            DataManager.getInstance().newPlaylistAsync(playlistName, error  -> {
                if(!isAdded()) return;

                btnAddPlaylist.setEnabled(true); // Re-enable button

                // Check if an error occurred during playlist creation
                if(error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show();
                    return;
                }

                // Playlist created successfully:
                refreshPlaylistList();
                editPlaylistName.setText("");
                hideKeyboard(editPlaylistName);
                Toast.makeText(context, "Playlist added", Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * Hides the soft keyboard from the screen.
     * @param view The view that currently has focus (e.g., the EditText).
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
     * Refreshes the list of playlists displayed in the RecyclerView.
     * Fetches the latest playlists from the DataManager asynchronously and updates the adapter.
     * Shows/hides the swipe refresh indicator.
     */
    public void refreshPlaylistList() {
        if (swipeRefreshLayout != null && !swipeRefreshLayout.isRefreshing()) {
            swipeRefreshLayout.setRefreshing(true);
        }

        Uri audioDir = DataManager.Settings.GetAudioDirectory();
        if (audioDir == null || getContext() == null) {
            if(swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // Fetch the playlists asynchronously using DataManager
        DataManager.getInstance().getPlaylistsAsync(playlists -> {
            if (!isAdded() || playlistAdapter == null) return;

            playlistList.clear();
            playlistList.addAll(playlists);
            playlistList.sort(Playlist.LATEST_COMPARATOR);
            playlistAdapter.notifyDataSetChanged();

            if(swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        });
    }
}