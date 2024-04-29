package cc.calliope.mini.fragment.home;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import cc.calliope.mini.WebInfoFragment;
import cc.calliope.mini.utils.Utils;

public class HomeAdapter extends FragmentStateAdapter {
    public HomeAdapter(Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if(position == 1 && Utils.isInternetAvailable()) {
            return WebInfoFragment.Companion.newInstance();
        }
        return HomeItemFragment.newInstance(position);
    }

    @Override
    public int getItemCount() {
        return Home.values().length;
    }
}