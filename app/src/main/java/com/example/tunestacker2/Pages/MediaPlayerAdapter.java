package com.example.tunestacker2.Pages;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.MusicPlayer.ThumbnailLoader;
import com.example.tunestacker2.R;

import java.util.List;

public class MediaPlayerAdapter extends RecyclerView.Adapter<MediaPlayerAdapter.SongViewHolder> {

    public interface MediaPlayerAdapterListener {
        void onSongClicked(int pos);
    }


    private final List<Song> songs;
    private final Context context;
    private final MediaPlayerAdapterListener listener;
    private int selectedPosition = RecyclerView.NO_POSITION;


    public MediaPlayerAdapter(Context context, List<Song> songs, int pos, MediaPlayerAdapterListener listener) {
        this.context = context;
        this.songs = songs;
        this.listener = listener;
        this.selectedPosition = pos;
    }

    public void selectSong(int position) {
        if (position < 0 || position >= songs.size()) return;
        if (position == selectedPosition) return;

        int oldPosition = selectedPosition;
        selectedPosition = position;

        // Only update the affected views
        if (oldPosition != RecyclerView.NO_POSITION) notifyItemChanged(oldPosition);
        notifyItemChanged(position);
    }


    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.playlist_item, parent, false);
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

        ThumbnailLoader.loadThumbnailAsync(song, context, bitmap -> {
            if (holder != null && holder.thumbnail != null &&
                    holder.thumbnail.getTag().equals(song.getAudioUri().toString())) {
                holder.thumbnail.setImageBitmap(bitmap);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSongClicked(position);
            }
        });

        if (position == selectedPosition) {
            holder.itemView.setBackgroundResource(R.drawable.search_bar_background);
        }
        else {
            holder.itemView.setBackgroundResource(android.R.color.transparent);
        }
    }


    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView songTitle;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.songThumbnail);
            songTitle = itemView.findViewById(R.id.songTitle);
        }
    }
}
