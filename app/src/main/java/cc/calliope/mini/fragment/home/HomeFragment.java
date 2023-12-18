package cc.calliope.mini.fragment.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import cc.calliope.mini.databinding.FragmentHomeBinding;
import cc.calliope.mini.fragment.ZoomOutPageTransformer;

public class HomeFragment extends Fragment {
    private FragmentHomeBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentHomeBinding.inflate(inflater, container, false);

        HomeAdapter adapter = new HomeAdapter(this);

        binding.homeViewpager.setAdapter(adapter);
        binding.homeViewpager.setPageTransformer(new ZoomOutPageTransformer());

        new TabLayoutMediator(binding.homeTabDots, binding.homeViewpager, (tab, position) ->
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