package cc.calliope.mini.fragment.editors;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class EditorsAdapter extends FragmentStateAdapter {
    public EditorsAdapter(Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return EditorsItemFragment.newInstance(position);
    }

    @Override
    public int getItemCount() {
        return Editor.values().length;
    }
}