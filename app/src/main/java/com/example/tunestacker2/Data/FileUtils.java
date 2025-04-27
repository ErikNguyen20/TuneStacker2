package com.example.tunestacker2.Data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.example.tunestacker2.MusicPlayer.Playlist;
import com.example.tunestacker2.MusicPlayer.Song;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Utility class for file operations, especially for audio files and JSON playlists.
 */
public class FileUtils {
    public static final String TAG = "FileUtils";


    /**
     * Retrieves the display name of a file from its Uri.
     *
     * @param context The context used to access the content resolver.
     * @param uri     The Uri of the file.
     * @return The display name of the file or null if not found.
     */
    public static String getFileNameFromUri(Context context, Uri uri) {
        String result = null;

        try (Cursor cursor = context.getApplicationContext().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file name from URI", e);
        }

        return result;
    }

    public static String removeExtensionFromName(String fileName) {
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(0, fileName.lastIndexOf(".")).trim();
        }
        return fileName;
    }

    /**
     * Gets the file name without its extension from a Uri.
     *
     * @param context The context used to access the content resolver.
     * @param uri     The Uri of the file.
     * @return The file name without the extension.
     */
    public static String getFileNameWithoutExtensionFromUri(Context context, Uri uri) {
        String name = getFileNameFromUri(context, uri);
        if (name != null && name.contains(".")) {
            return name.substring(0, name.lastIndexOf("."));
        }
        return name; // fallback: return full name if no extension
    }

    /**
     * Extracts the file extension from a Uri.
     *
     * @param context The context used to access the content resolver.
     * @param uri     The Uri of the file.
     * @return The file extension, or null if it cannot be determined.
     */
    public static String getExtensionFromUri(Context context, Uri uri) {
        String name = getFileNameFromUri(context, uri);
        if (name != null && name.contains(".")) {
            return name.substring(name.lastIndexOf(".") + 1);
        }
        return null;
    }

    /**
     * Returns the MIME type of a file based on its name or extension.
     *
     * @param fileName The name of the file.
     * @return The MIME type string or null if unknown.
     */
    public static String getMimeType(String fileName) {
        String extension;
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot != -1 && lastDot < fileName.length() - 1) {
            extension = fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        }
        else {
            extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        }

        // Manually override some extension to mime mappings to support order Android API versions
        switch (extension) {
            case "opus":
            case "oga":
                return "audio/ogg";
            case "flac":
                return "audio/flac";
            case "m4a":
            case "m4b":
                return "audio/mp4";
            case "json":
                return "application/json";
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Sanitizes a file name by removing unsafe or unsupported characters.
     *
     * @param fileName The original file name.
     * @return A safe and sanitized file name.
     */
    public static String sanitizeFilename(String fileName) {
        // Remotes leading and ending spaces.
        String valid = fileName.trim();
        // Limits character length to 250 characters.
        if(valid.length() > 250) {
            valid = valid.substring(0,250);
        }

        // Remove all control characters + DELETE(U+007F)
        // valid = valid.replaceAll("[\\x{0000}-\\x{001F}\\x{007F}]","");
        valid = valid.replaceAll("\\p{Cntrl}", "");

        return valid
                .replace(".", "．")    // Full width period
                .replace("/", "／")    // U+FF0F Fraction Slash
                .replace("\\", "＼")   // U+FF3C Fullwidth Reverse Solidus
                .replace(":", "꞉")     // U+A789 Modifier Letter Colon
                .replace("*", "＊")    // U+FF0A Fullwidth Asterisk
                .replace("?", "？")    // U+FF1F Fullwidth Question Mark
                .replace("\"", "＂")   // U+FF02 Fullwidth Quotation Mark
                .replace("<", "‹")     // U+2039 Single Left-Pointing Angle Quotation Mark
                .replace(">", "›")     // U+203A Single Right-Pointing Angle Quotation Mark
                .replace("|", "｜")    // U+FF5C
                .replace(";", ";")     // U+037E Greek Question Mark
                .replace("'", "’")     // U+2019 Right Single Quotation Mark
                .replace("&", "＆")    // U+FF06 Fullwidth Ampersand
                .replace("#", "＃");   // U+FF03 Fullwidth Number Sign
    }

    /**
     * Searches for a specific file name within a given directory Uri.
     *
     * @param context        The context used to access the content resolver.
     * @param directoryUri   The Uri of the directory to search within.
     * @param targetFileName The name of the file to search for.
     * @return The Uri of the file if found, otherwise null.
     */
    public static Uri findFileInDirectory(Context context, Uri directoryUri, String targetFileName) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri,
                DocumentsContract.getTreeDocumentId(directoryUri)
        );

        try (Cursor cursor = context.getApplicationContext().getContentResolver().query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                }, null, null, null)) {

            if (cursor != null) {
                // Iterate over every document and see if the file name matches
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String displayName = cursor.getString(1);

                    if (displayName != null && displayName.equalsIgnoreCase(targetFileName)) {
                        return DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding file in directory", e);
        }

        return null;
    }

    /**
     * Deletes the document associated with the given Uri.
     *
     * @param context The context used to access the content resolver.
     * @param uri     The Uri of the file to delete.
     * @return True if the file was deleted, false otherwise.
     */
    public static boolean deleteFileUri(Context context, Uri uri) {
        try {
            return DocumentsContract.deleteDocument(context.getApplicationContext().getContentResolver(), uri);
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file", e);
            return false;
        }
    }

    /**
     * Creates a new directory inside the specified parent Uri.
     *
     * @param context      The context used to access the content resolver.
     * @param parentTreeUri The Uri of the parent directory.
     * @param folderName   The name of the folder to create.
     * @return The Uri of the newly created directory or null on failure.
     */
    public static Uri createDirectory(Context context, Uri parentTreeUri, String folderName) {
        try {
            return DocumentsContract.createDocument(
                    context.getApplicationContext().getContentResolver(),
                    parentTreeUri,
                    DocumentsContract.Document.MIME_TYPE_DIR, // indicates folder
                    folderName
            );
        } catch (Exception e) {
            Log.e(TAG, "Error creating directory", e);
            return null;
        }
    }

    /**
     * Lists all audio files inside a given directory Uri.
     *
     * @param context      The context used to access the content resolver.
     * @param directoryUri The Uri of the directory to scan.
     * @return A list of Song objects found in the directory.
     */
    public static List<Song> listAudioFilesFromDirectory(Context context, Uri directoryUri) {
        List<Song> audioUris = new ArrayList<>();

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri,
                DocumentsContract.getTreeDocumentId(directoryUri)
        );

        try (Cursor cursor = context.getApplicationContext().getContentResolver().query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null, null, null)) {

            if (cursor != null) {
                // Iterate over each document in the directory to see if it is an audio file
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
                    String displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    long lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED));

                    // Fallback: try detecting mime type from file fails
                    if (mimeType == null || mimeType.equals("application/octet-stream")) {
                        mimeType = getMimeType(displayName);
                    }

                    if (mimeType != null && mimeType.startsWith("audio/")) {
                        // Add song to list
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId);
                        String targetTitle = FileUtils.removeExtensionFromName(displayName);
                        audioUris.add(new Song(targetTitle, fileUri, lastModified));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding audio files in directory", e);
        }

        return audioUris;
    }

    /**
     * Reads all JSON files in a directory and constructs Playlist objects from them.
     *
     * @param context      The context used to access the content resolver.
     * @param directoryUri The Uri of the directory containing the JSON files.
     * @return A list of playlists parsed from the JSON files.
     */
    @Deprecated
    public static List<Playlist> playlistsFromJsonFiles(Context context, Uri directoryUri) {
        if(directoryUri == null) return new ArrayList<>();

        List<Playlist> listOfPlaylists = new ArrayList<>();

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri,
                DocumentsContract.getTreeDocumentId(directoryUri)
        );
        try (Cursor cursor = context.getApplicationContext().getContentResolver().query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                },
                null, null, null)) {

            if (cursor != null) {
                // Iterate over each document in the directory to see if it is a json file
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
                    String displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));

                    // Fallback: try detecting mime type from file fails
                    if (mimeType == null || mimeType.equals("application/octet-stream")) {
                        mimeType = getMimeType(displayName);
                    }

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) continue;

                    if (mimeType.equals("application/json")) {
                        // If it is a json file, parse the playlist and add it to the list.
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId);
                        Playlist playlist = readPlaylistFromJsonFile(context.getApplicationContext(), fileUri, directoryUri);
                        if (playlist != null) {
                            listOfPlaylists.add(playlist);
                        }
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error finding all json files", e);
        }

        return listOfPlaylists;
    }

    /**
     * Parses a single JSON file into a Playlist object by resolving song references from the directory.
     *
     * @param context      The context used to access the content resolver.
     * @param jsonUri      The Uri of the playlist JSON file.
     * @param directoryUri The Uri of the directory containing the audio files.
     * @return A Playlist object constructed from the JSON file, or null on failure.
     */
    public static Playlist readPlaylistFromJsonFile(Context context, Uri jsonUri, Uri directoryUri) {
        if(jsonUri == null || directoryUri == null) return null;

        ContentResolver resolver = context.getApplicationContext().getContentResolver();

        try (InputStream inputStream = resolver.openInputStream(jsonUri);
             InputStreamReader isReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isReader)) {

            // Parse from reader
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                Log.e(TAG, "Invalid or empty JSON content.");
                return null;
            }
            JsonObject root = element.getAsJsonObject();

            // Ensures that the json file is a valid playlist
            if (!root.has("songs") || !root.has("name") || !root.has("lastPlayed")) {
                return null;
            }

            // Read the name and last played from the json
            String name = root.get("name").getAsString();
            long lastPlayed = root.get("lastPlayed").getAsLong();


            // Keeps track of songs in a set
            JsonArray songs = root.getAsJsonArray("songs");
            Set<String> playlistSet = new HashSet<>();
            List<String> playlistOrderedList = new ArrayList<>();
            Map<String, Song> playlistMap = new HashMap<>();
            for (int i = 0; i < songs.size(); i++) {
                String songName = songs.get(i).getAsString().trim();
                playlistSet.add(songName);
                playlistOrderedList.add(songName);
            }

            // Empty playlist, which is acceptable.
            if(playlistSet.isEmpty()) return new Playlist(jsonUri, null, name, lastPlayed);

            // Finds and builds up the songs list based on the set by iterating over every file in the directory
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    directoryUri,
                    DocumentsContract.getTreeDocumentId(directoryUri)
            );
            try (Cursor cursor = resolver.query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    },
                    null, null, null)) {

                if (cursor != null) {
                    // Iterate over each document in the directory to see if it is an audio file
                    while (cursor.moveToNext()) {
                        String docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                        String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
                        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                        long lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED));

                        // Fallback: try detecting mime type from file fails, must be a song
                        if (mimeType == null || mimeType.equals("application/octet-stream")) {
                            mimeType = getMimeType(displayName);
                        }

                        if (mimeType == null || !mimeType.startsWith("audio/") || displayName == null) {
                            continue;
                        }

                        // Add song to list (compares to file name without extension) if it is part of the playlist
                        String targetTitle = displayName;
                        if (displayName.contains(".")) {
                            targetTitle = displayName.substring(0, displayName.lastIndexOf("."));
                        }
                        if (playlistSet.contains(targetTitle.trim())) {
                            Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId);
                            playlistMap.put(targetTitle, new Song(targetTitle, fileUri, lastModified));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error iterating over items for the JSON reading", e);
            }

            // Constructs the list of songs
            List<Song> songList = new ArrayList<>();
            for (String title : playlistOrderedList) {
                if (playlistMap.containsKey(title)) {
                    songList.add(playlistMap.get(title));
                }
            }

            // Create and return the playlist
            return new Playlist(jsonUri, songList, name, lastPlayed);

        } catch (Exception e) {
            Log.e(TAG, "Error reading playlist JSON", e);
            return null;
        }
    }

    /**
     * Writes a Playlist object to a JSON file in the specified directory.
     *
     * @param context      The context used to access the content resolver.
     * @param playlist     The Playlist object to write.
     * @param directoryUri The directory where the JSON file should be saved.
     * @return True if the playlist was successfully written, false otherwise.
     */
    public static boolean writePlaylistToJsonFile(Context context, Playlist playlist, Uri directoryUri) {
        if (playlist == null || directoryUri == null) return false;

        ContentResolver resolver = context.getContentResolver();

        // Create at json file if it does not already exist
        Uri jsonUri = playlist.getJsonUri();
        if (jsonUri == null) {
            String fileName = playlist.getTitle() + ".json";
            try {
                Uri directoryDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                        directoryUri,
                        DocumentsContract.getTreeDocumentId(directoryUri)
                );
                jsonUri = DocumentsContract.createDocument(resolver, directoryDocumentUri, "application/json", fileName);
                if (jsonUri == null) {
                    Log.e(TAG, "Failed to create JSON file in directory");
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create or assign JSON file", e);
                return false;
            }
        }

        // Write to the json file
        try (OutputStream out = resolver.openOutputStream(jsonUri, "wt");
             OutputStreamWriter osWriter = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osWriter)) {

            // Set json fields from the playlist object
            JsonObject root = new JsonObject();
            root.addProperty("name", playlist.getTitle());
            root.addProperty("lastPlayed", playlist.getLastPlayed());

            JsonArray songArray = new JsonArray();
            List<Song> songs = playlist.getSongs();
            if (songs != null) {
                for (Song song : songs) {
                    songArray.add(song.getTitle());
                }
            }
            root.add("songs", songArray);

            // Write to the file
            Gson gson = new Gson();
            gson.toJson(root, writer);
            writer.flush();

            Log.d(TAG, "Playlist written to JSON successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to write playlist JSON", e);
            return false;
        }
    }

    /**
     * Converts an InputStream into a String using UTF-8 encoding.
     *
     * @param inputStream The input stream to read.
     * @return A String representation of the input stream's contents.
     * @throws IOException If an I/O error occurs during reading.
     */
    public static String streamToString(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    /**
     * Reads all JSON files in a directory and constructs Playlist objects from them.
     *
     * @param context      The context used to access the content resolver.
     * @param directoryUri The Uri of the directory containing the JSON files.
     * @return A list of playlists parsed from the JSON files.
     */
    public static List<Playlist> batchedPlaylistsFromJsonFiles(Context context, Uri directoryUri) {
        if(directoryUri == null) return new ArrayList<>();

        List<Playlist> listOfPlaylists = new ArrayList<>();
        Map<Uri, List<String>> playlistsSongsOrdered = new HashMap<>();
        Map<String, Song> audioUris = new HashMap<>();

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri,
                DocumentsContract.getTreeDocumentId(directoryUri)
        );
        try (Cursor cursor = context.getApplicationContext().getContentResolver().query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null, null, null)) {

            if (cursor != null) {
                // Iterate over each document in the directory to see if it is a json file
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE));
                    String displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    long lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED));


                    // Fallback: try detecting mime type from file fails
                    if (mimeType == null || mimeType.equals("application/octet-stream")) {
                        mimeType = getMimeType(displayName);
                    }
                    if (mimeType == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) continue;

                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId);
                    if (mimeType.equals("application/json")) {
                        // If it is a json file, parse the playlist and add it to the list.
                        ArrayList<String> songNames = new ArrayList<>();

                        // This parses everything but the songs
                        Playlist playlist = parsePlaylistFromJsonFile(context, fileUri, directoryUri, songNames);
                        if (playlist != null) {
                            listOfPlaylists.add(playlist);
                            playlistsSongsOrdered.put(playlist.getJsonUri(), songNames);
                        }
                    }
                    else if(mimeType.startsWith("audio/")) {
                        // Add song to list
                        String targetTitle = FileUtils.removeExtensionFromName(displayName);
                        audioUris.put(targetTitle.trim(), new Song(targetTitle, fileUri, lastModified));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding all json files", e);
        }

        // Build each playlist
        for(Playlist playlist : listOfPlaylists) {
            Uri jsonUri = playlist.getJsonUri();
            if(!playlistsSongsOrdered.containsKey(jsonUri)) {
                continue;
            }

            // Build the playlist with the set of songs in order
            List<String> songs = playlistsSongsOrdered.get(jsonUri);
            List<Song> toBeAdded = new ArrayList<>();
            for(String songName : songs) {
                if(audioUris.containsKey(songName)) {
                    toBeAdded.add(audioUris.get(songName));
                }
            }
            playlist.setSongs(toBeAdded);
        }

        return listOfPlaylists;
    }


    /**
     * Parses a single JSON file into a Playlist object. It does not resolve the songs.
     *
     * @param context      The context used to access the content resolver.
     * @param jsonUri      The Uri of the playlist JSON file.
     * @param directoryUri The Uri of the directory containing the audio files.
     * @return A Playlist object constructed from the JSON file, or null on failure.
     */
    public static Playlist parsePlaylistFromJsonFile(Context context, Uri jsonUri, Uri directoryUri, ArrayList<String> songNames) {
        if(jsonUri == null || directoryUri == null) return null;

        ContentResolver resolver = context.getApplicationContext().getContentResolver();

        try (InputStream inputStream = resolver.openInputStream(jsonUri);
             InputStreamReader isReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isReader)) {

            // Parse from reader
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                Log.e(TAG, "Invalid or empty JSON content.");
                return null;
            }
            JsonObject root = element.getAsJsonObject();

            // Ensures that the json file is a valid playlist
            if (!root.has("songs") || !root.has("name") || !root.has("lastPlayed")) {
                return null;
            }

            // Read the name and last played from the json
            String name = root.get("name").getAsString();
            long lastPlayed = root.get("lastPlayed").getAsLong();


            // Keeps track of songs in a set
            JsonArray songs = root.getAsJsonArray("songs");
            for (int i = 0; i < songs.size(); i++) {
                String songName = songs.get(i).getAsString().trim();
                songNames.add(songName);
            }

            // Empty playlist, which is acceptable.
            return new Playlist(jsonUri, null, name, lastPlayed);
        } catch (Exception e) {
            Log.e(TAG, "Error reading playlist JSON", e);
            return null;
        }
    }
}