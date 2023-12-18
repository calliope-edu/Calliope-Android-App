package cc.calliope.mini.fragment.home;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class HomeAdapter extends FragmentStateAdapter {
    public HomeAdapter(Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return HomeItemFragment.newInstance(position);
    }

    @Override
    public int getItemCount() {
        return Home.values().length;
    }
}