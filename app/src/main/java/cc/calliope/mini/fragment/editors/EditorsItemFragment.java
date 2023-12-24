package cc.calliope.mini.fragment.editors;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.FragmentItemBinding;
import cc.calliope.mini.utils.Preference;
import cc.calliope.mini.utils.Utils;

public class EditorsItemFragment extends Fragment {
    private static final String ARG_POSITION = "arg_position";
    private FragmentItemBinding binding;
    private final AlphaAnimation buttonClick = new AlphaAnimation(1F, 0.75F);
    private Editor editor;

    public static EditorsItemFragment newInstance(int position) {
        EditorsItemFragment fragment = new EditorsItemFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_POSITION, position);
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentItemBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Activity activity = getActivity();
        Bundle args = getArguments();
        if (args == null || activity == null)
            return;

        int position = args.getInt(ARG_POSITION);
        editor = Editor.values()[position];


        binding.titleTextView.setText(editor.getTitleResId());
        binding.iconImageView.setImageResource(editor.getIconResId());
        binding.infoTextView.setText(editor.getInfoResId());

        binding.infoTextView.setOnClickListener(this::openEditor);
        view.setOnClickListener(this::openEditor);
    }

    private void showWebFragment(String url, String editorName) {
        Activity activity = getActivity();
        if (activity == null){
            return;
        }

        NavController navController = Navigation.findNavController(activity, R.id.navigation_host_fragment);
        NavDirections webFragment = EditorsFragmentDirections.actionEditorsToWeb(url, editorName);
        navController.navigate(webFragment);
    }
    private void networkSecurityConfig(String url){
//        try {
//            // Завантаження поточної конфігурації
//            int resId = getResources().getIdentifier("network_security_config", "xml", getPackageName());
//            NetworkSecurityConfig config = NetworkSecurityConfigProvider.getNetworkSecurityConfig(getApplicationContext());
//
//            // Додавання нового домену до виключень
//            if (config != null) {
//                Set<Domain> domains = new HashSet<>(config.getDefaultConfig().getPermittedDomains());
//                domains.add(new Domain(domain));
//                NetworkSecurityConfig.Builder builder = NetworkSecurityConfig.Builder.fromConfig(config);
//                builder.addPermittedDomains(domains);
//                NetworkSecurityConfig newConfig = builder.build();
//
//                // Встановлення нової конфігурації
//                NetworkSecurityConfigProvider.setInstance(newConfig);
//            }
//        } catch (IOException e) {
//            // Обробка помилки
//            e.printStackTrace();
//        }
    }

    private void openEditor(View view){
        Activity activity = getActivity();
        if (activity == null){
            return;
        }

        view.startAnimation(buttonClick);
        if (Utils.isNetworkConnected(activity)) {
            String url = editor.getUrl();
            if (editor == Editor.CUSTOM) {
                url = Preference.getCustomLink(getContext());
            }
            showWebFragment(url, editor.toString());
        } else {
            Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_internet)).show();
        }
    }
}