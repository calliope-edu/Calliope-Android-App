package cc.calliope.mini.fragment.editors;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import cc.calliope.mini.fragment.ZoomOutPageTransformer;
import cc.calliope.mini.databinding.FragmentEditorsBinding;

public class EditorsFragment extends Fragment{
    private FragmentEditorsBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentEditorsBinding.inflate(inflater, container, false);

        EditorsAdapter adapter = new EditorsAdapter(this);

        binding.editorViewpager.setAdapter(adapter);
        binding.editorViewpager.setPageTransformer(new ZoomOutPageTransformer());

        new TabLayoutMediator(binding.editorTabDots, binding.editorViewpager, (tab, position) ->
                tab.setTabLabelVisibility(TabLayout.TAB_LABEL_VISIBILITY_UNLABELED))
                .attach();

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}