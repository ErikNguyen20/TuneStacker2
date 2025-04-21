package com.example.tunestacker2.Pages;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainActivityViewPagerAdapter extends FragmentStateAdapter {
    public MainActivityViewPagerAdapter(@NonNull FragmentActivity fa) {
        super(fa);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0: return new LibraryFragment();
            case 1: return new PlaylistFragment();
            case 2: return new SettingsFragment();
            default: return new PlaylistFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
