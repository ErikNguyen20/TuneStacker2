package com.example.tunestacker2.Pages;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
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
        ThumbnailLoader.loadThumbnailAsync(song, context, bitmap -> {
            if (holder != null && holder.thumbnail != null &&
                    holder.thumbnail.getTag().equals(song.getAudioUri().toString())) {
                holder.thumbnail.setImageBitmap(bitmap);
            }
        });

        // Regular click event
        holder.itemView.setOnClickListener(v -> {
            if (isMultiSelectMode) {
                toggleSelection(position);
                if (listener != null) {
                    listener.onToggleSelection(position);
                }
            } else {
                if (listener != null) {
                    listener.onSongClicked(position);
                }
            }
        });

        // Long-press to enter multi-select mode
        holder.itemView.setOnLongClickListener(v -> {
            if (!isMultiSelectMode) {
                setMultiSelectMode(true);
                toggleSelection(position);
                if (listener != null) {
                    listener.activateLongPress();
                }
                return true;
            }
            return false;
        });

        // Options menu click event
        holder.optionsButton.setOnClickListener(v -> {
            Context wrapper = new ContextThemeWrapper(context, R.style.AppTheme_PopupOverlay);
            PopupMenu popup = new PopupMenu(wrapper, holder.optionsButton);
            popup.inflate(R.menu.song_item_menu);
            popup.setOnMenuItemClickListener(item -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return true;

                if (item.getItemId() == R.id.menu_play) {
                    if (listener != null) {
                        listener.onSongClicked(pos);
                    }
                    return true;
                } else if (item.getItemId() == R.id.menu_delete) {
                    if (listener != null) {
                        listener.onSongDeleted(pos);
                    }
                    return true;
                } else if(item.getItemId() == R.id.menu_add) {
                    if (listener != null) {
                        listener.onSongAddPlaylist(pos);
                    }
                    return true;
                }
                return false;
            });
            popup.show();
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

