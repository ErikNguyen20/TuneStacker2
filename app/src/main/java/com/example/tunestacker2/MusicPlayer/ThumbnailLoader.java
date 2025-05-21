package com.example.tunestacker2.MusicPlayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.collection.LruCache;

import com.example.tunestacker2.R;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A utility class to load and cache thumbnail images for songs asynchronously or synchronously.
 */
public class ThumbnailLoader {
    // --- Constants ---
    private static final String LOG = "ThumbnailLoader";

    public static final int DEFAULT_THUMBNAIL = R.drawable.default_thumbail_2;

    // --- Cache and Background Operations ---
    private static final LruCache<String, Bitmap> cache64x64 = new LruCache<>(256); // cache ~256 images
    private static final ExecutorService executor = Executors.newFixedThreadPool(2); // adjust thread count as needed
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile Bitmap defaultThumbnail = null;

    /**
     * Callback interface for asynchronous thumbnail loading.
     */
    public interface ThumbnailCallback {
        void onThumbnailLoaded(Bitmap bitmap);
    }

    /**
     * Retrieves the default thumbnail image.
     *
     * @param context The application context.
     * @return A default thumbnail bitmap.
     */
    private static Bitmap getDefaultThumbnail(Context context) {
        if (defaultThumbnail == null) {
            synchronized (ThumbnailLoader.class) {
                if (defaultThumbnail == null) {
                    defaultThumbnail = BitmapFactory.decodeResource(
                            context.getApplicationContext().getResources(), DEFAULT_THUMBNAIL);
                }
            }
        }
        return defaultThumbnail;
    }

    /**
     * Loads a 64x64 thumbnail for a song asynchronously.
     *
     * @param song     The song for which to load the thumbnail.
     * @param context  The application context.
     * @param callback The callback to receive the loaded thumbnail.
     */
    public static void loadThumbnailAsync(Song song, Context context, ThumbnailCallback callback) {
        if(song == null || song.getAudioUri() == null) {
            mainHandler.post(() -> callback.onThumbnailLoaded(getDefaultThumbnail(context)));
            return;
        }

        Uri audioUri = song.getAudioUri();
        synchronized (cache64x64) {
            Bitmap cached = cache64x64.get(audioUri.toString());
            if (cached != null) {
                mainHandler.post(() -> callback.onThumbnailLoaded(cached));
                return;
            }
        }

        executor.execute(() -> {
            Bitmap bitmap = null;

            // Try embedded image
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(context.getApplicationContext(), audioUri);
                byte[] art = mmr.getEmbeddedPicture();
                if (art != null) {
                    bitmap = decodeSampledBitmapFromBytes(art, 64, 64);
                }
            } catch (Exception e) {
                Log.e(LOG, "Error loading thumbnail", e);
            } finally {
                try {
                    mmr.release();
                } catch (IOException ignore) {}
            }

            // Fallback to default
            if (bitmap == null) {
                bitmap = getDefaultThumbnail(context);
            }

            synchronized (cache64x64) {
                cache64x64.put(audioUri.toString(), bitmap);
            }
            Bitmap finalBitmap = bitmap;
            mainHandler.post(() -> {
                callback.onThumbnailLoaded(finalBitmap);
            });
        });
    }

    /**
     * Loads a 64x64 thumbnail for a song synchronously from cache or returns the default.
     *
     * @param song    The song whose thumbnail is to be retrieved.
     * @param context The application context.
     * @return A thumbnail bitmap, either from cache or default.
     */
    @Deprecated
    public static Bitmap loadThumbnailNonNullSync(Song song, Context context) {
        if(song == null || song.getAudioUri() == null) {
            return getDefaultThumbnail(context);
        }

        Uri audioUri = song.getAudioUri();
        synchronized (cache64x64) {
            Bitmap cached = cache64x64.get(audioUri.toString());
            if (cached != null) {
                return cached;
            }
        }

        return getDefaultThumbnail(context);
    }

    /**
     * Loads a 64x64 thumbnail for a song synchronously from cache or returns the default.
     *
     * @param song    The song whose thumbnail is to be retrieved.
     * @return A thumbnail bitmap, either from cache or default.
     */
    public static Bitmap loadThumbnailSync(Song song) {
        if(song == null || song.getAudioUri() == null) {
            return null;
        }

        Uri audioUri = song.getAudioUri();
        synchronized (cache64x64) {
            return cache64x64.get(audioUri.toString());
        }
    }

    /**
     * Loads a larger (256x256) thumbnail for a song asynchronously.
     *
     * @param song     The song for which to load the thumbnail.
     * @param context  The application context.
     * @param callback The callback to receive the loaded thumbnail.
     */
    public static void loadLargeThumbnailAsync(Song song, Context context, ThumbnailCallback callback) {
        if(song == null || song.getAudioUri() == null) {
            return;
        }

        Uri audioUri = song.getAudioUri();
        executor.execute(() -> {
            Bitmap bitmap = null;

            // Try embedded image
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(context.getApplicationContext(), audioUri);
                byte[] art = mmr.getEmbeddedPicture();
                if (art != null) {
                    bitmap = decodeSampledBitmapFromBytes(art, 256, 256);
                }
            } catch (Exception e) {
                Log.e(LOG, "Error loading thumbnail", e);
            } finally {
                try {
                    mmr.release();
                } catch (IOException ignore) {}
            }

            // Only callback when the bitmap is loaded
            if (bitmap != null) {
                Bitmap finalBitmap = bitmap;
                mainHandler.post(() -> {
                    callback.onThumbnailLoaded(finalBitmap);
                });
            }
        });
    }

    /**
     * Decodes a bitmap from byte array with downsampling.
     *
     * @param data      The raw image byte data.
     * @param reqWidth  The requested width.
     * @param reqHeight The requested height.
     * @return A sampled bitmap.
     */
    private static Bitmap decodeSampledBitmapFromBytes(byte[] data, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;


        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /**
     * Calculates the appropriate sample size for downsampling.
     *
     * @param options   Bitmap options containing original dimensions.
     * @param reqWidth  The requested width.
     * @param reqHeight The requested height.
     * @return The sample size to use.
     */
    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}

