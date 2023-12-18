package cc.calliope.mini.dialog.scripts;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import cc.calliope.mini.activity.FlashingActivity;
import cc.calliope.mini.FileWrapper;
import cc.calliope.mini.R;
import cc.calliope.mini.dialog.DialogUtils;
import cc.calliope.mini.utils.StaticExtra;
import cc.calliope.mini.ExtendedBluetoothDevice;
import cc.calliope.mini.databinding.FragmentScriptsBinding;
import cc.calliope.mini.fragment.editors.Editor;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.Version;
import cc.calliope.mini.viewmodels.ScannerViewModel;
import cc.calliope.mini.views.SimpleDividerItemDecoration;

import static android.app.Activity.RESULT_OK;


public class ScriptsFragment extends BottomSheetDialogFragment {
    private static final String TAG = "ScriptsFragment";
    private static final String FILE_EXTENSION = ".hex";
    private FragmentScriptsBinding binding;
    private FragmentActivity activity;
    private ScriptsRecyclerAdapter scriptsRecyclerAdapter;
    private ExtendedBluetoothDevice device;
    private FrameLayout bottomSheet;
    private int state = BottomSheetBehavior.STATE_COLLAPSED;
    private String sourceFilePath;

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
            new BottomSheetBehavior.BottomSheetCallback() {
                @Override
                public void onStateChanged(@NonNull View bottomSheet, int newState) {
                    state = newState;
                }

                @Override
                public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                }
            };

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(dialogInterface -> {
            BottomSheetDialog d = (BottomSheetDialog) dialogInterface;
            bottomSheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior.from(bottomSheet).addBottomSheetCallback(bottomSheetCallback);
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        });
        return dialog;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentScriptsBinding.inflate(inflater, container, false);
        activity = requireActivity();

        ScannerViewModel scannerViewModel = new ViewModelProvider(activity).get(ScannerViewModel.class);
        scannerViewModel.getScannerState().observe(getViewLifecycleOwner(), result -> device = result.getCurrentDevice());

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        BottomSheetBehavior.from(bottomSheet).removeBottomSheetCallback(bottomSheetCallback);
        binding = null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ArrayList<FileWrapper> filesList = new ArrayList<>();
        for (Editor editor : Editor.values()) {
            filesList.addAll(getFiles(editor));
        }
        TextView infoTextView = binding.infoTextView;
        RecyclerView recyclerView = binding.scriptsRecyclerView;

        if (filesList.isEmpty()) {
            infoTextView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.INVISIBLE);
        } else {
            infoTextView.setVisibility(View.INVISIBLE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setItemAnimator(new DefaultItemAnimator());
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            scriptsRecyclerAdapter = new ScriptsRecyclerAdapter(filesList);
            scriptsRecyclerAdapter.setOnItemClickListener(this::openDfuActivity);
            scriptsRecyclerAdapter.setOnItemLongClickListener(this::openPopupMenu);
            recyclerView.setAdapter(scriptsRecyclerAdapter);
            recyclerView.addItemDecoration(new SimpleDividerItemDecoration(activity));
        }
    }

    private ArrayList<FileWrapper> getFiles(Editor editor) {
        File[] filesArray = new File(activity.getFilesDir().toString() + File.separator + editor).listFiles();

        ArrayList<FileWrapper> filesList = new ArrayList<>();

        if (filesArray != null) {
            for (File file : filesArray) {
                String name = file.getName();
                if (name.contains(FILE_EXTENSION)) {
                    filesList.add(new FileWrapper(file, editor));
                }
            }
        }
        return filesList;
    }

    private void openDfuActivity(FileWrapper file) {
        if (device != null && device.isRelevant()) {
            final Intent intent = new Intent(activity, FlashingActivity.class);
            intent.putExtra(StaticExtra.EXTRA_DEVICE, device);
            intent.putExtra(StaticExtra.EXTRA_FILE_PATH, file.getAbsolutePath());
            startActivity(intent);
        } else {
            if (state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior<View> bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
            Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_no_connected)).show();

        }
    }

    private void openPopupMenu(View view, FileWrapper file) {
        PopupMenu popup = new PopupMenu(view.getContext(), view);
        popup.setOnMenuItemClickListener(item -> {
            //Non-constant Fields
            int id = item.getItemId();
            if (id == R.id.copy) {
                copyFile(file);
                return true;
            } else if (id == R.id.share) {
                shareFile(file);
                return true;
            } else if (id == R.id.rename) {
                renameFile(file);
                return true;
            } else if (id == R.id.remove) {
                removeFile(file);
                return true;
            }
            return false;

        });
        popup.inflate(R.menu.scripts_popup_menu);
        popup.show();
    }

    private void renameFile(FileWrapper file) {
        String title = getResources().getString(R.string.title_dialog_rename);
        String input = FilenameUtils.removeExtension(file.getName());

        DialogUtils.showEditDialog(activity, title, input, output -> {
            File dir = new File(FilenameUtils.getFullPath(file.getAbsolutePath()));
            if (dir.exists()) {
                FileWrapper dest = new FileWrapper(new File(dir, output + FILE_EXTENSION), file.getEditor());
                if (file.exists()) {
                    if (!dest.exists() && file.renameTo(dest.getFile())) {
                        scriptsRecyclerAdapter.change(file, dest);
                    } else {
                        Utils.errorSnackbar(binding.getRoot(), getString(R.string.error_snackbar_name_exists)).show();
                    }
                }
            }
        });
    }

    private void removeFile(FileWrapper file) {
        String title = getResources().getString(R.string.title_dialog_rename);
        String message = String.format(getString(R.string.info_dialog_delete), FilenameUtils.removeExtension(file.getName()));

        DialogUtils.showWarningDialog(activity, title, message, () -> {
            if (file.delete()) {
                scriptsRecyclerAdapter.remove(file);
            }
        });
    }

    private void shareFile(FileWrapper file) {
        if (file.exists()) {
            Uri uri = FileProvider.getUriForFile(activity, "cc.calliope.file_provider", file.getFile());
            Intent intent = new Intent(Intent.ACTION_SEND);

            intent.setType("text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.subject_dialog_share));
            intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.text_dialog_share));

            startActivity(Intent.createChooser(intent, getString(R.string.title_dialog_share)));
        }
    }

    public void copyFile(FileWrapper file){
        //TODO if(...)
        boolean connected = isMiniConnected();
        Utils.log(TAG, "Mini connected: " + connected);

        sourceFilePath = file.getAbsolutePath();

        if (Version.VERSION_Q_AND_NEWER) {
            openDocumentTreeNewApi();
        }else {
            openDocumentTree();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void openDocumentTreeNewApi() {
        StorageManager storageManager = (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);
        Intent intent = storageManager.getPrimaryStorageVolume().createOpenDocumentTreeIntent();

        String targetDirectory = "MINI"; // add your directory to be selected by the user
        Uri uri = intent.getParcelableExtra("android.provider.extra.INITIAL_URI");
        String scheme = uri.toString();
        scheme = scheme.replace("/root/", "/document/");
        scheme += "%3A" + targetDirectory;
        uri = Uri.parse(scheme);
        intent.putExtra("android.provider.extra.INITIAL_URI", uri);
        treeUriResultLauncher.launch(intent);
    }

    private void openDocumentTree(){
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        treeUriResultLauncher.launch(intent);
    }

    ActivityResultLauncher<Intent> treeUriResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Utils.log(TAG, "getResultCode: " + result.getResultCode());
                Utils.log(TAG, "getData: " + result.getData());
                int resultCode = result.getResultCode();
                Intent data = result.getData();

                if (resultCode == RESULT_OK) {
                    if (data != null) {
                        Uri treeUri = data.getData();

                        // treeUri is the Uri of the file
                        // If lifelong access is required, use takePersistableUriPermission()
                        activity.getContentResolver().takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
                        Utils.log(TAG, "treeUri: " + treeUri);
                        writeFile(treeUri);
                    }
                }
            }
    );

    public void writeFile(Uri uri) {
        try {
            DocumentFile directory = DocumentFile.fromTreeUri(activity, uri);
            DocumentFile file = directory.createFile("application/octet-stream", "firmware.hex");

            FileInputStream inputStream = new FileInputStream(sourceFilePath);

            ParcelFileDescriptor parcelFileDescriptor = activity.getContentResolver().openFileDescriptor(file.getUri(), "w");
            FileOutputStream outputStream = new FileOutputStream(parcelFileDescriptor.getFileDescriptor());

            FileChannel sourceChannel = inputStream.getChannel();
            FileChannel destinationChannel = outputStream.getChannel();

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());

            sourceChannel.close();
            destinationChannel.close();
            inputStream.close();
            outputStream.close();
        } catch (IOException e) {
            Utils.log(Log.ERROR, TAG, "IOException: " + e.getMessage());
        }
    }

    private boolean isMiniConnected(){
        UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            Utils.log(Log.DEBUG, "USB_Device", "Device Name: " + device.getDeviceName());
            Utils.log(Log.DEBUG, "USB_Device", "Product Name: " + device.getProductName());
            Utils.log(Log.DEBUG, "USB_Device", "Manufacturer Name: " + device.getManufacturerName());
            Utils.log(Log.DEBUG, "USB_Device", "Device Protocol: " + device.getDeviceProtocol());

            String productName = device.getProductName();
            if(productName != null && productName.contains("Calliope")) {
                Utils.log(Log.ASSERT, TAG, "it`s Calliope");
                return true;
            }
        }
        return false;
    }
}