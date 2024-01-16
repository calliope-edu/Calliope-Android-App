package cc.calliope.mini;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class FlashingBaseService extends Service implements ProgressListener{
    private ProgressCollector progressCollector;

    @Override
    public void onCreate() {
        super.onCreate();
        progressCollector = new ProgressCollector(this);
        progressCollector.registerReceivers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        progressCollector.unregisterReceivers();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDeviceConnecting() {

    }

    @Override
    public void onProcessStarting() {

    }

    @Override
    public void onAttemptDfuMode() {

    }

    @Override
    public void onEnablingDfuMode() {

    }

    @Override
    public void onFirmwareValidating() {

    }

    @Override
    public void onDeviceDisconnecting() {

    }

    @Override
    public void onCompleted() {

    }

    @Override
    public void onAborted() {

    }

    @Override
    public void onStartDfuService(int hardwareVersion) {

    }

    @Override
    public void onProgressChanged(int percent) {

    }

    @Override
    public void onBonding(@NonNull BluetoothDevice device, int bondState, int previousBondState) {

    }

    @Override
    public void onError(int code, String message) {

    }
}