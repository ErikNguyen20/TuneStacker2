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

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;


/**
 * ForegroundDownloadService handles downloading audio from URLs in a foreground service.
 * It uses youtube-dl to perform the downloads and provides notifications to the user.
 */
public class ForegroundDownloadService extends Service {
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

        // youtubeDLDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "tunestacker-ytdlp");
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
            startDownload(url);
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
     * Starts the download process for a given URL.
     *
     * @param url The URL to download from.
     */
    private void startDownload(String url) {
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

        downloadFuture = executor.submit(() -> {
            try {
                Log.i(ForegroundDownloadService.TAG, "Download Started.");
                File downloadedFile = yt_dlp_download(url);

                // If the file was correctly downloaded
                moveFileToTargetUri(downloadedFile, DataManager.Settings.GetAudioDirectory());
                updateNotification("Download complete.", "", 100, true);
                Log.i(ForegroundDownloadService.TAG, "Download Complete.");
            } catch (Exception e) {
                // If an exception occurred and it failed to download
                updateNotification("Unexpected error occurred.", (e.getMessage() != null) ? e.getMessage() : "", 0, false);
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    /**
     * Downloads a file using youtube-dl.
     *
     * @param url The URL to download from.
     * @return The downloaded file.
     * @throws RuntimeException     If an error occurs during download.
     * @throws InterruptedException If the download is interrupted.
     */
    private File yt_dlp_download(String url) throws RuntimeException, InterruptedException {
        // Retrieve information about the video
        String title = null;

        boolean retry = true;
        final int MAX_FETCH_RETRIES = 6;
        for (int i = 0; i < MAX_FETCH_RETRIES && retry; i++) {
            // Check if thread is canceled
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");

            try {
                updateNotification("Downloading...", "Fetching Info. Attempt " + (i + 1) + "/" + MAX_FETCH_RETRIES,  0, false);
                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("--no-playlist");
                // request.addOption("--no-check-certificates");
                VideoInfo streamInfo = YoutubeDL.getInstance().getInfo(request);
                title = streamInfo.getTitle();
                retry = false;
            } catch (YoutubeDLException | InterruptedException | YoutubeDL.CanceledException e) {
                // Check if thread is canceled
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");

                if(e.getMessage() != null) {
                    if(e.getMessage().contains("not a valid URL.")) {
                        throw new RuntimeException("Not a valid URL.");
                    } else if(e.getMessage().contains("Video unavailable")) {
                        throw new RuntimeException("Video is unavailable.");
                    } else if(e.getMessage().contains("Private video")) {
                        throw new RuntimeException("Video is Private.");
                    }
                }
                Log.e(ForegroundDownloadService.TAG, "Unexpected error on fetch - " + ((e.getMessage() != null) ? e.getMessage() : "An Error has Occurred"));

                // If it failed the last attempt, then return an error.
                if(i == MAX_FETCH_RETRIES - 1) {
                    throw new RuntimeException("Fetch Error - " + ((e.getMessage() != null) ? e.getMessage() : "An Error has Occurred"));
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(2000 + 1000*i, 4001 + 1000*i));
            }
        }
        // Check if thread is canceled
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");


        // Sanitize the title, check if file already was downloaded
        if(title == null) {
            Log.e(ForegroundDownloadService.TAG, "Failed to fetch video info.");
            throw new RuntimeException("Failed to fetch video info.");
        }
        title = FileUtils.sanitizeFilename(title);
        String ext = DataManager.Settings.GetFileExtension();
        if (FileUtils.findFileInDirectory(getApplicationContext(), DataManager.Settings.GetAudioDirectory(), title + "." + ext) != null) {
            Log.e(ForegroundDownloadService.TAG, "File already exists!");
            throw new RuntimeException("File already exists.");
        }


        //Performs a download operation.
        retry = true;
        final int MAX_DOWNLOAD_RETRIES = 3;
        for (int i = 0; i < MAX_DOWNLOAD_RETRIES && retry; i++) {
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");

                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("-x");
                request.addOption("--no-playlist");
                request.addOption("--retries", 10);
                // request.addOption("--no-check-certificates");
                // request.addOption("--extractor-args", "youtube:player_client=default,-web"); // ADDED RECENTLY
                request.addOption("--no-mtime");
                if (DataManager.Settings.GetEmbedThumbnail()) {
                    request.addOption("--embed-thumbnail");
                }
                request.addOption("--min-sleep-interval", 1 + 2*i);
                request.addOption("--max-sleep-interval", 4 + 3*i);
                request.addOption("--sleep-requests", 1 + 2*i);
                request.addOption("--retry-sleep", 2 + 2*i);
                request.addOption("--limit-rate", "2M");
                request.addOption("--audio-format", ext);
                request.addOption("-o", youtubeDLDir.getAbsolutePath() + File.separator + title + ".%(ext)s");

                // Implement the YoutubeDL.Callback interface directly.
                String finalTitle = title;
                DownloaderCallback callback = new DownloaderCallback() {
                    @Override
                    public void onProgressUpdate(float progress, long etaInSeconds, String line) {
                        updateNotification(finalTitle, "Downloading: " + "% (ETA " + etaInSeconds + "s)", (int) (progress * 100), false);
                    }
                };
                YoutubeDLCallbackAdapter adapter = new YoutubeDLCallbackAdapter(callback);
                YoutubeDL.getInstance().execute(request, PROCESS_ID, adapter);
                retry = false;
            } catch (YoutubeDL.CanceledException | InterruptedException e) {
                throw new InterruptedException("Thread is canceled.");
            } catch (YoutubeDLException e) {
                Log.e(ForegroundDownloadService.TAG, "Unexpected error on download - " + ((e.getMessage() != null) ? e.getMessage() : "An Error has Occurred"));

                // If it failed the last attempt, then return an error.
                if(i == MAX_DOWNLOAD_RETRIES - 1) {
                    throw new RuntimeException("Download Error - " + ((e.getMessage() != null) ? e.getMessage() : "An Error has Occurred"));
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(4000 + 1000*i, 8001 + 1000*i));
            }
        }

        // Returns the file
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Thread is canceled.");
        return new File(youtubeDLDir, title + "." + ext);
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
