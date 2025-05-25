package com.example.tunestacker2.Pages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.SongViewHolder> {

    public interface SongAdapterListener {
        void onSongDeleted(int pos);
        void onSongClicked(int pos);
        void onSongAddPlaylist(int pos);
        void onSongCopy(int pos);
        void onSongShare(int pos);
        void activateLongPress();
        void onToggleSelection(int pos);
    }


    private final List<Song> songs;

    private boolean isMultiSelectMode = false;
    private final Set<Song> selectedSongUris = new HashSet<>();

    private final Context context;
    private final SongAdapterListener listener;

    public LibraryAdapter(Context context, List<Song> songs, SongAdapterListener listener) {
        this.context = context;
        this.songs = songs;
        this.listener = listener;
    }

    public void setMultiSelectMode(boolean enabled) {
        if (isMultiSelectMode == enabled) return;

        isMultiSelectMode = enabled;
        if (!enabled) {
            selectedSongUris.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isMultiSelectMode() {
        return isMultiSelectMode;
    }

    public void toggleSelection(int position) {
        if (position < 0 || position >= songs.size()) return;

        Song song = songs.get(position);
        if (selectedSongUris.contains(song)) {
            selectedSongUris.remove(song);
        } else {
            selectedSongUris.add(song);
        }
        notifyItemChanged(position);
    }

    public void selectAll() {
        for (Song song : songs) {
            selectedSongUris.add(song);
        }
        notifyDataSetChanged();
    }

    public void clearSelections() {
        selectedSongUris.clear();
        notifyDataSetChanged();
    }

    public Set<Song> getSelectedSongs() {
        return selectedSongUris;
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

        // Show or hide the checkbox
        holder.checkBox.setVisibility(isMultiSelectMode ? View.VISIBLE : View.GONE);
        holder.checkBox.setChecked(selectedSongUris.contains(song));


        // Hide options menu in selection mode
        holder.optionsButton.setVisibility(isMultiSelectMode ? View.GONE : View.VISIBLE);


        // Load the thumbnail asynchronously
        Bitmap img = ThumbnailLoader.loadThumbnailSync(song);
        if (img != null) {
            holder.thumbnail.setImageBitmap(img);
        } else {
            holder.thumbnail.setImageResource(ThumbnailLoader.DEFAULT_THUMBNAIL);
            ThumbnailLoader.loadThumbnailAsync(song, context.getApplicationContext(), bitmap -> {
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

            if (isMultiSelectMode) {
                toggleSelection(pos);
                if (listener != null) {
                    listener.onToggleSelection(pos);
                }
            } else {
                if (listener != null) {
                    listener.onSongClicked(pos);
                }
            }
        });

        // Long-press to enter multi-select mode
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;

            if (!isMultiSelectMode) {
                setMultiSelectMode(true);
                toggleSelection(pos);
                if (listener != null) {
                    listener.activateLongPress();
                }
                return true;
            }
            return false;
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

        View popupView = LayoutInflater.from(context).inflate(R.layout.song_item_options_popup, null);
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
        popupView.findViewById(R.id.option_add).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongAddPlaylist(pos);
        });
        popupView.findViewById(R.id.option_copy).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongCopy(pos);
        });
        popupView.findViewById(R.id.option_share).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongShare(pos);
        });
        popupView.findViewById(R.id.option_delete).setOnClickListener(v -> {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            if (listener != null) listener.onSongDeleted(pos);
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

