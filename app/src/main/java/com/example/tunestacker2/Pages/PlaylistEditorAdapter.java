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
        holder.thumbnail.setImageBitmap(ThumbnailLoader.loadThumbnailSync(song, context.getApplicationContext()));
        ThumbnailLoader.loadLargeThumbnailAsync(song, context.getApplicationContext(), bitmap -> {
            if (holder != null && holder.thumbnail != null &&
                    holder.thumbnail.getTag().equals(song.getAudioUri().toString())) {
                holder.thumbnail.setImageBitmap(bitmap);
            }
        });

        // Regular click event
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSongClicked(position);
            }
        });

        // Options menu click event
        holder.optionsButton.setOnClickListener(v -> {
            Context wrapper = new ContextThemeWrapper(context, R.style.AppTheme_PopupOverlay);
            PopupMenu popup = new PopupMenu(wrapper, holder.optionsButton);
            popup.inflate(R.menu.playlist_editor_song_item_menu);
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
                } else if(item.getItemId() == R.id.menu_moveup) {
                    if (listener != null) {
                        listener.onSongMoveUp(pos);
                    }
                } else if(item.getItemId() == R.id.menu_movedown) {
                    if (listener != null) {
                        listener.onSongMoveDown(pos);
                    }
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

