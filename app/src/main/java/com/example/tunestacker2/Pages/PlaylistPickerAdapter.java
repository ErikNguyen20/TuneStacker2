package com.example.tunestacker2.Pages;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tunestacker2.R;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlaylistPickerAdapter extends RecyclerView.Adapter<PlaylistPickerAdapter.ViewHolder> {

    private final List<String> playlistNames;
    private final Set<String> selectedPlaylists = new HashSet<>();

    public PlaylistPickerAdapter(List<String> playlistNames) {
        this.playlistNames = playlistNames;
    }

    public Set<String> getSelectedPlaylists() {
        return selectedPlaylists;
    }

    @NonNull
    @Override
    public PlaylistPickerAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.playlist_picker_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistPickerAdapter.ViewHolder holder, int position) {
        String name = playlistNames.get(position);
        holder.playlistName.setText(name);
        holder.playlistCheckbox.setOnCheckedChangeListener(null);
        holder.playlistCheckbox.setChecked(selectedPlaylists.contains(name));

        holder.playlistCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedPlaylists.add(name);
            } else {
                selectedPlaylists.remove(name);
            }
        });

        // Also toggle checkbox when clicking on the row
        holder.itemView.setOnClickListener(v -> {
            boolean isChecked = !holder.playlistCheckbox.isChecked();
            holder.playlistCheckbox.setChecked(isChecked);
        });
    }

    @Override
    public int getItemCount() {
        return playlistNames.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox playlistCheckbox;
        TextView playlistName;

        ViewHolder(View itemView) {
            super(itemView);
            playlistCheckbox = itemView.findViewById(R.id.playlistCheckBox);
            playlistName = itemView.findViewById(R.id.playlistTitle);
        }
    }
}

