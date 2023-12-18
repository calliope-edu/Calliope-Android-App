package cc.calliope.mini;

import android.bluetooth.BluetoothDevice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;

import static cc.calliope.mini.service.DfuControlService.HardwareVersion;

public interface ProgressListener {
    @IntDef({BOND_BONDING, BOND_BONDED, BOND_NONE})
    @Retention(RetentionPolicy.SOURCE)
    @interface BondState {
    }

    void onDeviceConnecting();
    void onProcessStarting();
    void onAttemptDfuMode();
    void onEnablingDfuMode();
    void onFirmwareValidating();
    void onDeviceDisconnecting();
    void onCompleted();
    void onAborted();
    void onStartDfuService(@HardwareVersion final int hardwareVersion);
    void onProgressChanged(int percent);
    void onBonding(@NonNull BluetoothDevice device, @BondState int bondState, @BondState int previousBondState);
    void onError(int code, String message);
}