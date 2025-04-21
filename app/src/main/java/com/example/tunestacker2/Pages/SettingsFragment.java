package com.example.tunestacker2.Pages;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import com.example.tunestacker2.Data.DataManager;
import com.example.tunestacker2.R;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link Fragment} subclass responsible for displaying and managing application settings.
 * This includes selecting the audio download directory, choosing the file format,
 * toggling thumbnail embedding, managing auto-updates, and forcing library updates.
 *
 * Activities containing this fragment must implement the {@link UpdateRequestListener} interface
 * to handle update requests and directory changes triggered by this fragment.
 */
public class SettingsFragment extends Fragment {
    /**
     * Interface definition for callbacks to the hosting Activity.
     * Allows the fragment to notify the activity about user actions related to updates.
     */
    public interface UpdateRequestListener {
        /**
         * Called when the user explicitly requests a library update (e.g., by pressing the "Force Update" button).
         */
        void onUpdateRequested();

        /**
         * Called when the user successfully selects a new audio download directory.
         */
        void onDirectorySwitch();
    }

    // --- Constants ---
    private static final String TAG = "SettingsFragment"; // Tag for logging

    // --- UI Elements ---
    private Button pickDirectoryButton;
    private Spinner fileExtensionPicker;
    private MaterialSwitch embedThumbnailSwitch;
    private MaterialSwitch autoUpdateSwitch;
    private Button forceUpdateButton;

    // Activity Result Launcher
    private ActivityResultLauncher<Intent> openDirectoryLauncher;

    // List of supported file extensions
    private final List<String> fileExtensions = new ArrayList<>(Arrays.asList("opus", "mp3", "m4a"));

    // Listener interface for communicating events back to the hosting Activity.
    private SettingsFragment.UpdateRequestListener listener;


    /**
     * Required empty public constructor for Fragment instantiation.
     */
    public SettingsFragment() {
        // Required empty public constructor
    }

    // --- Lifecycle Methods ---

    /**
     * Called when the fragment is first attached to its context (Activity).
     * Ensures the hosting Activity implements the {@link UpdateRequestListener}.
     * Initializes the {@link #openDirectoryLauncher}.
     *
     * @param context The application context.
     */
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof SettingsFragment.UpdateRequestListener) {
            listener = (SettingsFragment.UpdateRequestListener) context;
        } else {
            throw new RuntimeException(context + " must implement UpdateRequestListener");
        }
    }

    /**
     * Called to have the fragment instantiate its user interface view.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container          If non-null, this is the parent view that the fragment's UI should be attached to.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state.
     * @return Return the View for the fragment's UI, or null.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize UI elements
        initializeViews(view);

        // Initialize the directory launcher
        initializeDirectoryLauncher();

        // Initialize settings and listeners
        setupInitialSettingsAndListeners();
    }

    /**
     * Called when the fragment is being detached from its Activity.
     * Cleans up the listener reference to avoid memory leaks.
     */
    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    // --- Initialization Methods ---

    /**
     * Finds and assigns all UI elements from the inflated view.
     *
     * @param view The root view of the fragment's layout.
     */
    private void initializeViews(@NonNull View view) {
        pickDirectoryButton = view.findViewById(R.id.pickDirectoryButton);
        fileExtensionPicker = view.findViewById(R.id.fileExtensionPicker);
        embedThumbnailSwitch = view.findViewById(R.id.embedThumbnailSwitch);
        forceUpdateButton = view.findViewById(R.id.forceUpdateButton);
        autoUpdateSwitch = view.findViewById(R.id.autoUpdateSwitch);
    }

    /**
     * Initializes the {@link #openDirectoryLauncher} used to handle the result
     * of the directory selection intent.
     */
    private void initializeDirectoryLauncher() {
        openDirectoryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Check if the result is OK (user selected a directory)
                    if(result.getResultCode() != Activity.RESULT_OK) {
                        Log.e(TAG, "User did not select a directory.");
                        return;
                    }

                    // Check if the intent data is null
                    Intent data = result.getData();
                    if (data == null) {
                        Log.e(TAG, "Intent data is null.");
                        return;
                    }

                    // Check if the selected directory data is null
                    Uri directoryUri = data.getData();
                    if (directoryUri == null) {
                        Log.e(TAG, "Selected directory URI is null.");
                        return;
                    }

                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    try {
                        requireActivity().getContentResolver().takePersistableUriPermission(directoryUri, takeFlags);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Failed to take persistable URI permission.");
                        e.printStackTrace(); // Or log it
                    }

                    // Save the selected directory URI using DataManager
                    DataManager.Settings.SetAudioDirectory(directoryUri);
                    displayUriName(directoryUri);
                    if (listener != null) listener.onDirectorySwitch();
                }
        );
    }

    /**
     * Loads the initial settings from {@link DataManager} and populates the UI elements
     * with these saved values (directory, file extension, switch states).
     */
    private void setupInitialSettingsAndListeners() {
        // Set up the directory selection button
        pickDirectoryButton.setOnClickListener(v -> openDirectoryPicker());

        // Display the selected directory URI
        displayUriName(DataManager.Settings.GetAudioDirectory());

        // Set up the file extension spinner
        setupFileExtensionSpinner();
        fileExtensionPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedExtension = (String) parent.getItemAtPosition(position);
                DataManager.Settings.SetFileExtension(selectedExtension);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Set up the thumbnail switch listener
        embedThumbnailSwitch.setChecked(DataManager.Settings.GetEmbedThumbnail());
        embedThumbnailSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DataManager.Settings.SetEmbedThumbnail(isChecked);
        });

        // Set up the update switch listener
        autoUpdateSwitch.setChecked(DataManager.Settings.GetAutoUpdate());
        autoUpdateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DataManager.Settings.SetAutoUpdate(isChecked);
        });

        // Set up the force update button
        forceUpdateButton.setOnClickListener(v -> {
            if(listener != null) listener.onUpdateRequested();
        });
    }

    /**
     * Configures the file extension {@link Spinner}, populating it with available extensions
     * and setting up its item selection listener to save the chosen extension.
     */
    private void setupFileExtensionSpinner() {
        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, fileExtensions); // simple_spinner_item
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        fileExtensionPicker.setAdapter(adapter);
        int index = fileExtensions.indexOf(DataManager.Settings.GetFileExtension());
        fileExtensionPicker.setSelection(Math.max(index, 0));

    }

    /**
     * Launches the system's directory picker intent (ACTION_OPEN_DOCUMENT_TREE).
     * The result is handled by the {@link #openDirectoryLauncher}.
     */
    private void openDirectoryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        openDirectoryLauncher.launch(intent);
    }

    /**
     * Updates the text of the {@link #pickDirectoryButton} to display the name
     * of the selected directory URI. If the URI is null, displays a default message.
     *
     * @param uri The {@link Uri} of the selected directory, or null if none is selected.
     */
    private void displayUriName(Uri uri) {
        if (uri == null) {
            pickDirectoryButton.setText("No directory selected.");
            return;
        }
        pickDirectoryButton.setText(uri.toString());
    }
}