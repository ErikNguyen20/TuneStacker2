package com.example.tunestacker2.Data;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.documentfile.provider.DocumentFile;

import com.example.tunestacker2.MusicPlayer.Song;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;


/**
 * ForegroundDownloadService handles downloading audio from URLs in a foreground service.
 * It uses youtube-dl to perform the downloads and provides notifications to the user.
 */
public class ForegroundDownloadService extends Service {
    public interface DownloadCallback {
        void progressUpdate(int progress, String titleText, String contentText);
        void downloadComplete();
        void downloadError(String errorMessage);
        void downloadShutdown();
    }

    // --- Constants ---
    public static final String TAG = "FOREGROUND_DOWNLOAD_SERVICE";
    public static final String BROADCAST_ACTION_IDENTIFIER = "FOREGROUND_DOWNLOAD_SERVICE_PROCESS";
    public static final String CHANNEL_ID = "DownloadChannel";
    public static final String INTENT_URL_KEY = "intent_download_url";
    public static final String INTENT_PROGRESS_KEY = "intent_download_progress";
    public static final String INTENT_MAIN_TEXT_KEY = "intent_download_main_text";
    public static final String INTENT_CONTENT_TEXT_KEY = "intent_download_content_text";
    public static final String INTENT_TERMINATE_KEY = "intent_download_terminate";
    public static final int NOTIF_ID = 1001;
    private static final String PROCESS_ID = "DownloadProcessID";

    private static final int MAX_FETCH_RETRIES = 3;
    private static final int MAX_DOWNLOAD_RETRIES = 4;
    private static final int BASE_SLEEP_MS = 1000;
    private static final int MAX_SLEEP_MS = 32000;

    private File youtubeDLDir;
    private ExecutorService executor;
    private volatile Future<?> downloadFuture;


    /**
     * Called when the service is first created.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, getNotification("Download starting...", "", 0));

        youtubeDLDir = new File(getApplicationContext().getFilesDir(), "tunestacker-ytdlp");
        if (!youtubeDLDir.exists()) {
            youtubeDLDir.mkdirs();
        }

        executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Called when a client starts the service using startService().
     *
     * @param intent  The Intent supplied to startService().
     * @param flags   Additional data about this start request.
     * @param startId A unique integer representing this specific request to start.
     * @return The return value indicates what semantics the system should use for the service's
     * current started state.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(ForegroundDownloadService.INTENT_URL_KEY)) {
            String url = intent.getStringExtra(ForegroundDownloadService.INTENT_URL_KEY);
            processDownloadRequest(url);
        }

        return START_NOT_STICKY;
        //return START_REDELIVER_INTENT;
    }

    /**
     * Required method for bound services. Returns null since this is a started service.
     *
     * @param intent The intent used to bind.
     * @return Always null.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Stops the service and cancels any ongoing downloads.
     *
     * @param name The Intent used to stop the service.
     * @return True if the service was successfully stopped.
     */
    @Override
    public boolean stopService(Intent name) {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
        } catch (Exception e) {
            Log.e(ForegroundDownloadService.TAG, "stopService - " + ((e.getMessage() != null) ? e.getMessage() : "An Error has Occurred"));
        }

        // Interrupt the thread
        if (downloadFuture != null && !downloadFuture.isDone()) {
            downloadFuture.cancel(true);
        }

        // Force shutdown if needed
        if (executor != null) {
            executor.shutdownNow();
        }

        updateNotification("Download canceled.", "", 0, false);
        return super.stopService(name);
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    /**
     * Determines the platform a URL belongs to.
     */
    public static boolean isValidPlatform(String url) {
        if (url == null) return false;
        url = url.toLowerCase();

        return url.contains("youtube.com") ||
               url.contains("youtu.be") ||
               url.contains("soundcloud.com") ||
               url.contains("bandcamp.com") ||
               url.contains("vimeo.com") ||
               url.contains("dailymotion.com") ||
               url.contains("twitch.tv") ||
               url.contains("bilibili.com") ||
               url.contains("nicovideo.jp") ||
               url.contains("niconico");
    }

    /**
     * Checks if a URL is a playlist link based on known patterns.
     */
    public static boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        url = url.toLowerCase();

        return url.contains("/playlist") ||           // General pattern (YouTube, SoundCloud, Bandcamp, etc.)
               url.contains("/sets/") ||              // SoundCloud sets
               url.contains("/album/") ||             // Bandcamp albums
               url.contains("/channel/") ||           // Vimeo, Dailymotion
               url.contains("/collections/") ||       // Twitch collections
               url.contains("/series/");              // Bilibili, Niconico, etc.
    }

    /**
     * Starts the download process for a given URL.
     *
     * @param url The URL to download from.
     */
    private void processDownloadRequest(String url) {
        synchronized (this) {
            if (downloadFuture != null && !downloadFuture.isDone()) {
                Log.w(ForegroundDownloadService.TAG, "Download already in progress. Ignoring new request.");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            if (!isNetworkAvailable(this)) {
                Log.w(ForegroundDownloadService.TAG, "No network connection. Download will not start.");
                updateNotification("Download failed", "No network connection.", 0, false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }

            if (!isPlaylistUrl(url)) {
                // Download single song
                downloadSongProcedure(url, new DownloadCallback() {
                    @Override
                    public void progressUpdate(int progress, String titleText, String contentText) {
                        updateNotification(titleText, contentText, progress, false);
                    }

                    @Override
                    public void downloadComplete() {
                        updateNotification("Download complete.", "", 100, true);
                    }

                    @Override
                    public void downloadError(String errorMessage) {
                        updateNotification("An error occurred.", (errorMessage != null) ? errorMessage : "", 0, false);
                    }

                    @Override
                    public void downloadShutdown() {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                });
            } else {
                // Download playlist
                downloadPlaylistProcedure(url, new DownloadCallback() {
                    @Override
                    public void progressUpdate(int progress, String titleText, String contentText) {
                        updateNotification(titleText, contentText, progress, false);
                    }

                    @Override
                    public void downloadComplete() {
                        updateNotification("Download complete.", "", 100, true);
                    }

                    @Override
                    public void downloadError(String errorMessage) {
                        updateNotification("An error occurred.", (errorMessage != null) ? errorMessage : "", 0, false);
                    }

                    @Override
                    public void downloadShutdown() {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                });
            }
        }
    }

    /**
     * Orchestrates the full download process for a single YouTube video.
     * <p>
     * This method runs asynchronously in a background thread and performs the following:
     * <ul>
     *   <li>Fetches video metadata using yt-dlp</li>
     *   <li>Sanitizes the video title and checks if the file already exists</li>
     *   <li>Constructs a {@link YoutubeDLRequest} with appropriate metadata options</li>
     *   <li>Downloads the audio file with progress reporting via {@link DownloadCallback}</li>
     *   <li>Moves the downloaded file to the target audio directory</li>
     * </ul>
     * <p>
     * It ensures proper error handling, cancellation support (via thread interruption),
     * and guaranteed invocation of {@code downloadShutdown()} after completion or failure.
     *
     * @param url      The URL of the YouTube video to download.
     * @param callback A {@link DownloadCallback} to report progress, success, failure, and shutdown.
     */
    private void downloadSongProcedure(String url, DownloadCallback callback) {
        downloadFuture = executor.submit(() -> {
            try {
                // Retrieve information about the video
                VideoInfo streamInfo = getVideoInfo(url, callback);

                // Sanitize the title, check if file already was downloaded
                if(streamInfo.getTitle() == null) {
                    Log.e(ForegroundDownloadService.TAG, "Failed to fetch video info.");
                    throw new RuntimeException("Failed to fetch video info.");
                }
                String title = FileUtils.sanitizeFilename(streamInfo.getTitle());
                if(title.isEmpty()) {
                    Log.e(ForegroundDownloadService.TAG, "Failed to sanitize title (empty).");
                    throw new RuntimeException("Failed to sanitize title (empty).");
                }
                String artist = streamInfo.getUploader();
                String ext = DataManager.Settings.GetFileExtension();
                if (FileUtils.findFileInDirectory(getApplicationContext(), DataManager.Settings.GetAudioDirectory(), title) != null) {
                    Log.e(ForegroundDownloadService.TAG, "This Audio File already exists!");
                    throw new RuntimeException("File already exists.");
                }

                YoutubeDLRequest request = buildDownloadRequest(url, title, ext, artist);

                File downloadedFile = downloadSong(title, ext, request, callback);
                moveFileToTargetUri(downloadedFile, DataManager.Settings.GetAudioDirectory());
                callback.downloadComplete();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                callback.downloadError(e.getMessage());
            } finally {
                callback.downloadShutdown();
            }
        });
    }

    /**
     * Initiates the download procedure for a YouTube playlist.
     * <p>
     * This method:
     * <ul>
     *     <li>Retrieves playlist metadata via {@code GetPlaylistInfo}</li>
     *     <li>Determines which files are missing in the audio directory</li>
     *     <li>Downloads missing tracks sequentially using {@code YoutubeDL}</li>
     *     <li>Handles errors gracefully on a per-track basis</li>
     *     <li>Notifies the given {@link DownloadCallback} on completion, error, or shutdown</li>
     * </ul>
     * </p>
     *
     * The entire process runs in a background thread using {@code executor.submit()} and can be canceled
     * safely via {@code downloadFuture.cancel(true)}.
     *
     * @param url      The URL of the YouTube playlist to be downloaded.
     * @param callback The callback used to receive download progress, completion, error, and shutdown events.
     */
    private void downloadPlaylistProcedure(String url, DownloadCallback callback) {
        downloadFuture = executor.submit(() -> {
            try {
                // Retrieve information about the playlist
                Map<String, PlaylistVideoInfo> playlistInfo = getPlaylistInfo(url, callback);
                Set<String> missingNames = FileUtils.findMissingFilesInDirectory(getApplicationContext(), DataManager.Settings.GetAudioDirectory(), playlistInfo.keySet());

                String ext = DataManager.Settings.GetFileExtension();
                for(String name : missingNames) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread canceled.");

                    try {
                        // Attempt to download this particular song
                        PlaylistVideoInfo streamInfo = playlistInfo.get(name);
                        if (streamInfo == null) continue;

                        String title = streamInfo.getTitle();
                        String audio_url = streamInfo.getUrl();
                        String artist = streamInfo.getUploader();

                        YoutubeDLRequest request = buildDownloadRequest(audio_url, title, ext, artist);
                        File downloadedFile = downloadSong(title, ext, request, callback);
                        moveFileToTargetUri(downloadedFile, DataManager.Settings.GetAudioDirectory());

                        // Sleep with random delay
                        int sleepTime = ThreadLocalRandom.current().nextInt(BASE_SLEEP_MS, BASE_SLEEP_MS * 4);
                        Thread.sleep(sleepTime);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e; // Rethrow the interrupted exception

                    } catch (Exception e) {
                        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        Log.e(ForegroundDownloadService.TAG, "Failed to download song: " + name + " | " + errorMessage);
                    }
                }

                callback.downloadComplete();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                callback.downloadError(e.getMessage());
            } finally {
                callback.downloadShutdown();
            }
        });
    }

    /**
     * Constructs a configured {@link YoutubeDLRequest} for downloading audio from a video URL.
     *
     * This method sets flags for metadata embedding, thumbnail embedding, and download options
     * such as format, rate limiting, sleep intervals, and output path.
     *
     * @param url     The direct URL of the YouTube video to be downloaded.
     * @param title   The desired title of the downloaded file (used in metadata and output path).
     * @param ext     The audio format to download (e.g., "mp3", "m4a").
     * @param artist  The artist of the downloaded file (used in metadata and can be null).
     * @return A fully-configured {@link YoutubeDLRequest} ready for execution.
     */
    private YoutubeDLRequest buildDownloadRequest(String url, String title, String ext, String artist) {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("-x");
        request.addOption("--no-playlist");
        request.addOption("--retries", 10);
        request.addOption("--no-mtime");

        if (DataManager.Settings.GetEmbedThumbnail()) {
            request.addOption("--embed-thumbnail");
        }

        // Sets flags for metadata
        if(DataManager.Settings.GetEmbedMetadata()) {
            request.addOption("--embed-metadata");

            request.addOption("--parse-metadata", ":(?P<meta_title>" + title + ")");
            if(artist != null) {
                artist = FileUtils.sanitizeFilename(artist);
                request.addOption("--parse-metadata", ":(?P<meta_artist>" + artist + ")");
            }

            // Remove metadata fields that we don't care about
            request.addOption("--parse-metadata", ":(?P<meta_date>)");
            request.addOption("--parse-metadata", ":(?P<meta_description>)");
            request.addOption("--parse-metadata", ":(?P<meta_synopsis>)");
            request.addOption("--parse-metadata", ":(?P<meta_comment>)");
            request.addOption("--parse-metadata", ":(?P<meta_disc>)");
            request.addOption("--parse-metadata", ":(?P<meta_show>)");
            request.addOption("--parse-metadata", ":(?P<meta_season_number>)");
            request.addOption("--parse-metadata", ":(?P<meta_episode_id>)");
            request.addOption("--parse-metadata", ":(?P<meta_episode_sort>)");
        }

        // Sets workaround for youtube-dl
        request.addOption("--min-sleep-interval", 1);
        request.addOption("--max-sleep-interval", 4);
        request.addOption("--sleep-requests", 1);
        request.addOption("--retry-sleep", 1);
        request.addOption("--limit-rate", "2M");
        request.addOption("--audio-format", ext);
        request.addOption("-o", new File(youtubeDLDir, title + ".%(ext)s").getAbsolutePath());
        return request;
    }

    /**
     * Executes the audio download operation using a prepared {@link YoutubeDLRequest}.
     * Retries the download on failure, with randomized sleep intervals.
     *
     * This method also uses a {@link DownloadCallback} to report progress updates to the caller.
     * If the thread is interrupted at any point, the method cancels gracefully.
     *
     * @param title    The name of the audio file to be saved.
     * @param ext      The audio format for the download (e.g., "mp3").
     * @param request  The pre-configured {@link YoutubeDLRequest} for the download.
     * @param callback A callback interface to send progress updates (can be UI or background).
     * @return A {@link File} object representing the downloaded audio file.
     * @throws InterruptedException If the thread was canceled or interrupted.
     * @throws RuntimeException If the download fails after all retries.
     */
    private File downloadSong(String title, String ext, YoutubeDLRequest request, DownloadCallback callback) throws InterruptedException, RuntimeException {
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread canceled.");

            try {
                // Download the audio to internal youtube directory
                callback.progressUpdate(0, title, "Attempt " + attempt + "/" + MAX_DOWNLOAD_RETRIES);

                YtDLPDownloaderCallback execute_callback = new YtDLPDownloaderCallback() {
                    @Override
                    public void onProgressUpdate(float progress, long etaInSeconds, String line) {
                        if(etaInSeconds == -1) {
                            callback.progressUpdate(0, title, "Preparing Downloader...");
                            return;
                        }

                        int percentage = Math.abs((int) progress);
                        callback.progressUpdate(percentage, title, "Downloading: " + percentage + "% (ETA " + etaInSeconds + "s)");
                    }
                };
                YoutubeDLCallbackAdapter adapter = new YoutubeDLCallbackAdapter(execute_callback);
                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, PROCESS_ID, adapter);

                // Return the file
                return new File(youtubeDLDir, title + "." + ext);

            } catch (YoutubeDLException e) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread canceled.");

                // Log and report error
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                Log.e(ForegroundDownloadService.TAG, "Download error (attempt " + attempt + "): " + errorMessage);
                callback.progressUpdate(0, title, "Download error (attempt " + attempt + "): " + errorMessage);
                if (attempt == MAX_DOWNLOAD_RETRIES) {
                    throw new RuntimeException("Download failed after retries: " + errorMessage, e);
                }

                // Retry with a random delay
                int expBackoff = Math.min(BASE_SLEEP_MS * (1 << attempt), MAX_SLEEP_MS);
                int jitter = ThreadLocalRandom.current().nextInt(expBackoff / 2 + 1);
                try {
                    Thread.sleep(expBackoff + jitter);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ex; // Rethrow the interrupted exception
                }

            } catch (YoutubeDL.CanceledException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Thread canceled."); // Rethrow the interrupted exception
            }
        }

        throw new RuntimeException("Unexpected download loop exit."); // Should never reach
    }

    /**
     * Retrieves metadata information about a YouTube video via yt-dlp.
     * Retries on transient failures, using exponential backoff with jitter.
     *
     * Handles common known exceptions (invalid URL, unavailable or private video),
     * and gracefully exits on thread interruption.
     *
     * @param url      The YouTube video URL to retrieve metadata from.
     * @param callback A callback to report fetch progress to the caller.
     * @return A {@link VideoInfo} object containing detailed information about the video.
     * @throws InterruptedException If the thread was canceled or interrupted.
     * @throws RuntimeException If the fetch fails due to known video issues or after all retries.
     */
    private VideoInfo getVideoInfo(String url, DownloadCallback callback) throws InterruptedException, RuntimeException {
        for (int attempt = 1; attempt <= MAX_FETCH_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");

            try {
                // Retrieve information about the video
                callback.progressUpdate(0, "Fetching Info.", "Attempt " + attempt + "/" + MAX_FETCH_RETRIES);

                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("--no-playlist");
                return YoutubeDL.getInstance().getInfo(request);

            } catch (YoutubeDLException e) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");

                // Handle known exceptions
                if(e.getMessage() != null) {
                    if(e.getMessage().contains("not a valid URL.")) {
                        throw new RuntimeException("Not a valid URL.");
                    } else if(e.getMessage().contains("Video unavailable")) {
                        throw new RuntimeException("Video is unavailable.");
                    } else if(e.getMessage().contains("Private video")) {
                        throw new RuntimeException("Video is Private.");
                    }
                }
                // Log and report error
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                Log.e(ForegroundDownloadService.TAG, "Fetch error (attempt " + attempt + "): " + errorMessage);
                callback.progressUpdate(0, "Unexpected Error on Fetch", "Fetch error (attempt " + attempt + "): " + errorMessage);
                if (attempt == MAX_FETCH_RETRIES) {
                    throw new RuntimeException("Video Info failed after retries: " + errorMessage, e);
                }

                // Retry with a random delay
                int expBackoff = Math.min(BASE_SLEEP_MS * (1 << attempt), MAX_SLEEP_MS);
                int jitter = ThreadLocalRandom.current().nextInt(expBackoff / 2 + 1);
                try {
                    Thread.sleep(expBackoff + jitter);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ex; // Rethrow the interrupted exception
                }

            } catch (YoutubeDL.CanceledException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Thread canceled."); // Rethrow the interrupted exception
            }
        }

        throw new RuntimeException("Failed to fetch info after retries.");
    }

    /**
     * Executes the audio download operation using a prepared {@link YoutubeDLRequest}.
     * Retries the download on failure, with randomized sleep intervals.
     *
     * This method also uses a {@link DownloadCallback} to report progress updates to the caller.
     * If the thread is interrupted at any point, the method cancels gracefully.
     *
     * @param url      The name of the audio file to be saved.
     * @param callback A callback interface to send progress updates (can be UI or background).
     * @return A {@link List<Song>} object representing the downloaded audio file.
     * @throws InterruptedException If the thread was canceled or interrupted.
     * @throws RuntimeException If the download fails after all retries.
     */
    private Map<String, PlaylistVideoInfo> getPlaylistInfo(String url, DownloadCallback callback) throws InterruptedException, RuntimeException {
        for (int attempt = 1; attempt <= MAX_FETCH_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread canceled.");

            try {
                // Download the audio to internal youtube directory
                callback.progressUpdate(0, "Fetching Playlist Info.", "Attempt " + attempt + "/" + MAX_FETCH_RETRIES);

                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("--dump-single-json");
                request.addOption("--flat-playlist");
                request.addOption("--compat-options", "no-youtube-unavailable-videos");
                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, null);
                String jsonOutput = response.getOut();

                // Parse the JSON
                Map<String, PlaylistVideoInfo> videoList = new HashMap<>();
                JsonObject jsonObject = JsonParser.parseString(jsonOutput).getAsJsonObject();
                if (!jsonObject.has("entries") || !jsonObject.get("entries").isJsonArray()) {
                    throw new RuntimeException("Invalid JSON format from playlist!");
                }

                JsonArray entries = jsonObject.getAsJsonArray("entries");
                for (JsonElement entryElement : entries) {
                    JsonObject entry = entryElement.getAsJsonObject();
                    // Check if the entry is valid
                    if (!entry.has("title") || !entry.has("url")) {
                        continue;
                    }

                    // Extract the title and url
                    String title = entry.get("title").getAsString();
                    String video_url = entry.get("url").getAsString();
                    String uploader = entry.has("uploader") ? entry.get("uploader").getAsString() : null;
                    if (title == null || video_url == null || title.isEmpty() || video_url.isEmpty()) {
                        continue;
                    }
                    title = FileUtils.sanitizeFilename(title);
                    if(title.isEmpty()) continue;

                    videoList.put(title, new PlaylistVideoInfo(title, video_url, uploader));
                }

                // Return the video list
                return videoList;

            } catch (YoutubeDLException | IllegalStateException | JsonSyntaxException e) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread canceled.");

                // Log and report error
                String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                Log.e(ForegroundDownloadService.TAG, "Fetch error (attempt " + attempt + "): " + errorMessage);
                callback.progressUpdate(0, "Unexpected Error on Fetch", "Fetch error (attempt " + attempt + "): " + errorMessage);
                if (attempt == MAX_FETCH_RETRIES) {
                    throw new RuntimeException("Playlist Info failed after retries: " + errorMessage, e);
                }

                // Retry with a random delay
                int expBackoff = Math.min(BASE_SLEEP_MS * (1 << attempt), MAX_SLEEP_MS);
                int jitter = ThreadLocalRandom.current().nextInt(expBackoff / 2 + 1);
                try {
                    Thread.sleep(expBackoff + jitter);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ex; // Rethrow the interrupted exception
                }

            } catch (YoutubeDL.CanceledException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Thread canceled."); // Rethrow the interrupted exception
            }
        }

        throw new RuntimeException("Unexpected playlist info loop exit."); // Should never reach
    }


    /**
     * Moves a downloaded file into the SAF directory.
     *
     * @param sourceFile The local file to move.
     * @param treeUri    The SAF Uri representing the destination folder.
     */
    private void moveFileToTargetUri(File sourceFile, Uri treeUri) {
        DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
        if (pickedDir == null || !pickedDir.canWrite()) return;

        String mime = FileUtils.getMimeType(sourceFile.getName());
        String fileName = sourceFile.getName();

        // Create new file
        DocumentFile targetFile = pickedDir.createFile(mime, fileName);
        if (targetFile == null) {
            Log.e(ForegroundDownloadService.TAG, "Failed to create target file in SAF directory.");
            throw new RuntimeException("Failed to create target file in SAF directory.");
        }

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = getContentResolver().openOutputStream(targetFile.getUri())) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();

        } catch (Exception e) {
            Log.e(ForegroundDownloadService.TAG, "Failed to write target file in SAF directory.");
            throw new RuntimeException("Failed to write target file in SAF directory.");

        } finally {
            // Remove source file
            if(sourceFile.exists())  {
                sourceFile.delete();
            }
        }
    }

    /**
     * Creates a download notification with progress.
     *
     * @param mainText    Title of the notification.
     * @param contentText Description text.
     * @param progress    Progress value (0-100).
     * @return The constructed Notification.
     */
    private Notification getNotification(String mainText, String contentText, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(mainText)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        builder.setProgress(100, progress, false);
        return builder.build();
    }

    /**
     * Updates the current notification and broadcasts progress to UI components.
     *
     * @param mainText    Main notification title.
     * @param contentText Notification body text.
     * @param progress    Progress value.
     * @param terminate   Whether to signal that the operation is complete.
     */
    private void updateNotification(String mainText, String contentText, int progress, boolean terminate) {
        Notification notification = getNotification(mainText, contentText, progress);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(ForegroundDownloadService.TAG, "updateNotification - Failed to send notification!");
        }
        else {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification);
        }

        Intent progressIntent = new Intent();
        progressIntent.setAction(BROADCAST_ACTION_IDENTIFIER);
        progressIntent.setPackage(getPackageName());
        progressIntent.putExtra(ForegroundDownloadService.INTENT_PROGRESS_KEY, progress);
        progressIntent.putExtra(ForegroundDownloadService.INTENT_MAIN_TEXT_KEY, mainText);
        progressIntent.putExtra(ForegroundDownloadService.INTENT_CONTENT_TEXT_KEY, contentText);
        progressIntent.putExtra(ForegroundDownloadService.INTENT_TERMINATE_KEY, terminate);
        sendBroadcast(progressIntent);
    }

    /**
     * Creates a notification channel for Android O and above.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Download Service Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    /**
     * Utility method to check if network connectivity is available.
     *
     * @param context The application context.
     * @return True if network is available, false otherwise.
     */
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

}
