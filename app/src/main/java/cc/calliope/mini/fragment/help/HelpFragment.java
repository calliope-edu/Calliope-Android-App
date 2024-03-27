package cc.calliope.mini.fragment.help;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import cc.calliope.mini.R;
import cc.calliope.mini.activity.SettingsActivity;
import cc.calliope.mini.databinding.FragmentHelpBinding;

public class HelpFragment extends Fragment {
    private FragmentHelpBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentHelpBinding.inflate(inflater, container, false);

        TextView appInfo = binding.appInfo;
        appInfo.setMovementMethod(LinkMovementMethod.getInstance());
        Spanned spanned = Html.fromHtml(getString(R.string.info_app));
        appInfo.setText(spanned);

        // Adding menu items programmatically
        MenuItem shareMenuItem = binding.topAppBar.getMenu().add(R.string.title_settings);
        shareMenuItem.setOnMenuItemClickListener(item -> {
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            startActivity(intent);
            return true;
        });

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}