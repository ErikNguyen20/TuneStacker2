package com.example.tunestacker2.Pages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.MusicPlayer.ThumbnailLoader;
import com.example.tunestacker2.R;

import java.util.List;

public class PlaylistEditorAdapter extends RecyclerView.Adapter<PlaylistEditorAdapter.SongViewHolder> {

    public interface PlaylistEditorAdapterListener {
        void onSongDeleted(int pos);
        void onSongClicked(int pos);
        void onSongMoveUp(int pos);
        void onSongMoveDown(int pos);
    }


    private final List<Song> songs;

    private final Context context;
    private final PlaylistEditorAdapterListener listener;

    public PlaylistEditorAdapter(Context context, List<Song> songs, PlaylistEditorAdapterListener listener) {
        this.context = context;
        this.songs = songs;
        this.listener = listener;
    }


    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.song_item, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = songs.get(position);
        holder.songTitle.setText(song.getTitle());

        // Set placeholder first (important for recycling!)
        holder.thumbnail.setImageResource(ThumbnailLoader.DEFAULT_THUMBNAIL);

        // Tag the view to track recycling
        holder.thumbnail.setTag(song.getAudioUri().toString());

        // Load the thumbnail asynchronously
        Bitmap img = ThumbnailLoader.loadThumbnailSync(song);
        if (img != null) {
            holder.thumbnail.setImageBitmap(img);
        } else {
            holder.thumbnail.setImageResource(ThumbnailLoader.DEFAULT_THUMBNAIL);
            ThumbnailLoader.loadLargeThumbnailAsync(song, context.getApplicationContext(), bitmap -> {
                if (holder != null && holder.thumbnail != null &&
                        holder.thumbnail.getTag().equals(song.getAudioUri().toString())) {
                    holder.thumbnail.setImageBitmap(bitmap);
                }
            });
        }

        // Regular click event
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            if (listener != null) {
                listener.onSongClicked(pos);
            }
        });

        // Options menu click event
        holder.optionsButton.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            OpenOptionsDialog(holder.optionsButton, pos);
        });

        // Animation Effects
        holder.itemView.animate().cancel();
        holder.itemView.setScaleX(0.95f);
        holder.itemView.setScaleY(0.95f);
        holder.itemView.post(() -> {
            holder.itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(new OvershootInterpolator())
                    .setDuration(300)
                    .start();
        });
    }


    @Override
    public int getItemCount() {
        return songs.size();
    }

    /**
     * Opens the options dialog for a song.
     * @param anchor
     * @param pos
     */
    private void OpenOptionsDialog(View anchor, int pos) {
        if (context == null) return;

        View popupView = LayoutInflater.from(context).inflate(R.layout.playlist_editor_item_options_popup, null);
        PopupWindow popupWindow = new PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true
        );
        popupWindow.setElevation(12f);
        popupWindow.setOutsideTouchable(true);

        // Handle each options button click
        popupView.findViewById(R.id.option_play).setOnClickListener(v -> {
            if(popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongClicked(pos);
        });
        popupView.findViewById(R.id.option_remove).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongDeleted(pos);
        });
        popupView.findViewById(R.id.option_top).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongMoveUp(pos);
        });
        popupView.findViewById(R.id.option_bottom).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongMoveDown(pos);
        });

        // Ensures that the popup window is not cut off by the screen
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        Rect screenRect = new Rect();
        anchor.getWindowVisibleDisplayFrame(screenRect);
        int screenHeight = screenRect.height();

        // Get anchor position
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int spaceBelow = screenHeight - (location[1] + anchor.getHeight());

        // Show above or below based on available space
        if (spaceBelow >= popupHeight) {
            popupWindow.showAsDropDown(anchor, 0, 0);
        } else {
            int yOffset = -(popupHeight + anchor.getHeight());
            popupWindow.showAsDropDown(anchor, 0, yOffset);
        }
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView songTitle;
        ImageButton optionsButton;
        CheckBox checkBox;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.songThumbnail);
            songTitle = itemView.findViewById(R.id.songTitle);
            optionsButton = itemView.findViewById(R.id.optionsButton);
            checkBox = itemView.findViewById(R.id.songCheckBox);
        }
    }
}

