package cc.calliope.mini;

import android.bluetooth.BluetoothDevice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;

public interface ProgressListener {
    @IntDef({BOND_BONDING, BOND_BONDED, BOND_NONE})
    @Retention(RetentionPolicy.SOURCE)
    @interface BondState {
    }

    void onDfuAttempt();
    void onDfuControlComplete();
    void onProgressUpdate(int progress);
    void onBluetoothBondingStateChanged(@NonNull BluetoothDevice device, @BondState int bondState, @BondState int previousBondState);
    void onConnectionFailed();
    void onError(int code, String message);
}