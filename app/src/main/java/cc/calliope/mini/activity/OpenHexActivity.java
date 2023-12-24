package cc.calliope.mini.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import cc.calliope.mini.R;
import cc.calliope.mini.ExtendedBluetoothDevice;
import cc.calliope.mini.databinding.ActivityHexBinding;
import cc.calliope.mini.fragment.editors.Editor;
import cc.calliope.mini.utils.FileUtils;
import cc.calliope.mini.utils.StaticExtra;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.Version;
import cc.calliope.mini.viewmodels.ScannerLiveData;


public class OpenHexActivity extends ScannerActivity {
    private static final int REQUEST_CODE_PERMISSIONS = 123;
    private ActivityHexBinding binding;
    private boolean isStartFlashing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityHexBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Запитуємо дозвіл на читання зовнішнього сховища
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE_PERMISSIONS);
        }

        setPatternFab(binding.patternFab);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();
        String scheme = intent.getScheme();

        Log.w("HexActivity", "action: " + action);
        Log.w("HexActivity", "type: " + type);
        Log.w("HexActivity", "scheme: " + scheme);

        if (Intent.ACTION_VIEW.equals(action) && type != null /*&& type.equals("application/octet-stream")*/) {
            Uri uri = intent.getData();
            String decodedUri;

            try {
                decodedUri = URLDecoder.decode(uri.toString(), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }

            String name = FilenameUtils.getBaseName(decodedUri);

            binding.infoTextView.setText(
                    String.format(getString(R.string.open_hex_info), name)
            );

            binding.flashButton.setOnClickListener(v -> {
                try {
                    File file = FileUtils.getFile(this, Editor.LIBRARY.toString(), name);
                    if (file == null) {
                        return;
                    }

                    isStartFlashing = true;
                    copyFile(uri, file);
                    startDFUActivity(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        if (isStartFlashing) {
            finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (Version.VERSION_TIRAMISU_AND_NEWER || (requestCode == REQUEST_CODE_PERMISSIONS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            // Дозвіл на читання зовнішнього сховища отримано
            Log.w("HexActivity", "Дозвіл на читання зовнішнього сховища отримано");
        } else {
            finish();
        }
    }

    public void copyFile(Uri uri, File destFile) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        OutputStream outputStream = new FileOutputStream(destFile);

        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }

        outputStream.flush();
        outputStream.close();
        inputStream.close();
    }

    private void startDFUActivity(File file) {
        final Intent intent = new Intent(this, FlashingActivity.class);
        intent.putExtra(StaticExtra.EXTRA_FILE_PATH, file.getAbsolutePath());
        startActivity(intent);
    }
}