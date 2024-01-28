package cc.calliope.mini;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;

public class FlashingBaseService extends LifecycleService implements ProgressListener{
    private ProgressCollector progressCollector;

    @Override
    public void onCreate() {
        super.onCreate();
        progressCollector = new ProgressCollector(this);
        getLifecycle().addObserver(progressCollector);

        progressCollector.registerReceivers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        progressCollector.unregisterReceivers();
    }

    @Override
    public void onDfuAttempt() {

    }

    @Override
    public void onConnectionFailed(){

    }

    @Override
    public void onHardwareVersionReceived(int hardwareVersion) {

    }

    @Override
    public void onProgressUpdate(int percent) {

    }

    @Override
    public void onBluetoothBondingStateChanged(@NonNull BluetoothDevice device, int bondState, int previousBondState) {

    }

    @Override
    public void onError(int code, String message) {

    }
}