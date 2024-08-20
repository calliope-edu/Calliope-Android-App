package cc.calliope.mini;

import static no.nordicsemi.android.dfu.DfuBaseService.EXTRA_DATA;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import cc.calliope.mini.core.service.DfuService;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.error.GattError;

public class App extends Application {
    private static final String FILE_NAME = "one_time_pairing.hex";
    private static final String CUSTOM_DIR = "CUSTOM";

    private final BroadcastReceiver dfuServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case DfuService.BROADCAST_PROGRESS -> {
                    Utils.log(Log.ASSERT, "App", "Progress: " + intent.getIntExtra(EXTRA_DATA, 0));
                }
                case DfuService.BROADCAST_ERROR -> {
                    int code = intent.getIntExtra(EXTRA_DATA, 0);
                    int type = intent.getIntExtra(DfuBaseService.EXTRA_ERROR_TYPE, 0);
                    String message = switch (type) {
                        case DfuBaseService.ERROR_TYPE_COMMUNICATION_STATE ->
                                GattError.parseConnectionError(code);
                        case DfuBaseService.ERROR_TYPE_DFU_REMOTE ->
                                GattError.parseDfuRemoteError(code);
                        default -> GattError.parse(code);
                    };
                    Utils.log(Log.ASSERT, "App", "Error: " + message);
                }
            }
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        AppContext.initialize(this);
        copyFileToInternalStorage();
        registerReceivers();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        unregisterReceivers();
    }

    private void copyFileToInternalStorage() {
        File libraryDir = new File(getFilesDir(), CUSTOM_DIR);
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }

        File file = new File(libraryDir, FILE_NAME);
        if (!file.exists()) {
            try (InputStream inputStream = getResources().openRawResource(R.raw.one_time_pairing);
                 FileOutputStream outputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            } catch (IOException e) {
                Log.e("App", "Error copying file", e);
            }
        }
    }

    public void registerReceivers() {
        IntentFilter dfuServiceFilter = new IntentFilter();
        dfuServiceFilter.addAction(DfuService.BROADCAST_PROGRESS);
        dfuServiceFilter.addAction(DfuService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(dfuServiceReceiver, dfuServiceFilter);
    }

    public void unregisterReceivers() {
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(dfuServiceReceiver);
    }
}