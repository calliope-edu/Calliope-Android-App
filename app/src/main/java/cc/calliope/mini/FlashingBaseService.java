package cc.calliope.mini;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;

import cc.calliope.mini.utils.Utils;

public class FlashingBaseService extends LifecycleService implements ProgressListener{
    private static final String TAG = "FlashingService";
    private ProgressCollector progressCollector;

    @Override
    public void onCreate() {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onCreate");
        super.onCreate();
        progressCollector = new ProgressCollector(this);
        getLifecycle().addObserver(progressCollector);

        progressCollector.registerReceivers();
    }

    @Override
    public void onDestroy() {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onDestroy");
        super.onDestroy();
        progressCollector.unregisterReceivers();
    }

    @Override
    public void onDfuAttempt() {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onDfuAttempt");
    }

    @Override
    public void onConnectionFailed(){
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onConnectionFailed");
    }

    @Override
    public void onHardwareVersionReceived(int hardwareVersion) {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onHardwareVersionReceived");
    }

    @Override
    public void onProgressUpdate(int percent) {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onProgressUpdate");
    }

    @Override
    public void onBluetoothBondingStateChanged(@NonNull BluetoothDevice device, int bondState, int previousBondState) {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onBluetoothBondingStateChanged, bondState: " + bondState + " previousBondState: " + previousBondState);
    }

    @Override
    public void onError(int code, String message) {
        Utils.log(Log.ASSERT, TAG, "FlashingBaseService onError");
    }
}