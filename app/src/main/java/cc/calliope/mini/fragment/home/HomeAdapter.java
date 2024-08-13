package cc.calliope.mini.fragment.home;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import cc.calliope.mini.fragment.WebInfoFragment;
import cc.calliope.mini.fragment.help.HelpFragment;
import cc.calliope.mini.utils.Utils;

public class HomeAdapter extends FragmentStateAdapter {
    public HomeAdapter(Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return new HelpFragment();
        }
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