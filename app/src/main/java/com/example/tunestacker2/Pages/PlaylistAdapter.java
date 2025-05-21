package com.example.tunestacker2.Pages;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tunestacker2.MusicPlayer.Playlist;
import com.example.tunestacker2.MusicPlayer.Song;
import com.example.tunestacker2.MusicPlayer.ThumbnailLoader;
import com.example.tunestacker2.R;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {

    public interface PlaylistAdapterListener {
        void onPlaylistClicked(int pos);
    }

    private final List<Playlist> playlistList;
    private final Context context;
    private final PlaylistAdapterListener listener;

    public PlaylistAdapter(Context context, List<Playlist> playlistList, PlaylistAdapterListener listener) {
        this.playlistList = playlistList;
        this.context = context;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.large_playlist_item, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
        Playlist item = playlistList.get(position);
        List<Song> songs = item.getSongs();

        holder.textTitle.setText(item.getTitle());
        holder.textSubtitle.setText(songs.size() + " songs");

        // Tag the view to track recycling
        holder.songThumbnail.setTag(item.getTitle());

        // Load the thumbnail asynchronously
        if (!songs.isEmpty()) {
            Song song = songs.get(0);
            holder.songThumbnail.setImageBitmap(ThumbnailLoader.loadThumbnailNonNullSync(song, context.getApplicationContext()));
            ThumbnailLoader.loadLargeThumbnailAsync(song, context.getApplicationContext(), bitmap -> {
                if (holder != null && holder.songThumbnail != null &&
                        holder.songThumbnail.getTag().equals(item.getTitle())) {
                    holder.songThumbnail.setImageBitmap(bitmap);
                }
            });
        }
        else {
            // Sets to default if no songs
            holder.songThumbnail.setImageResource(ThumbnailLoader.DEFAULT_THUMBNAIL);
        }

        // Regular click event
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            if (listener != null) {
                listener.onPlaylistClicked(pos);
            }
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
        return playlistList.size();
    }

    static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView songThumbnail;
        TextView textTitle;
        TextView textSubtitle;

        public PlaylistViewHolder(@NonNull View itemView) {
            super(itemView);
            songThumbnail = itemView.findViewById(R.id.songThumbnail);
            textTitle = itemView.findViewById(R.id.textTitle);
            textSubtitle = itemView.findViewById(R.id.textSubtitle);
        }
    }
}

