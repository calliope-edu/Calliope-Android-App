package cc.calliope.mini;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;


import org.microbit.android.partialflashing.PartialFlashingBaseService;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import cc.calliope.mini.service.DfuControlService;
import cc.calliope.mini.service.DfuService;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.error.GattError;

import static android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED;
import static android.bluetooth.BluetoothDevice.ERROR;
import static android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE;

import static cc.calliope.mini.service.DfuControlService.UNIDENTIFIED;
import static cc.calliope.mini.service.DfuControlService.EXTRA_BOARD_VERSION;
import static cc.calliope.mini.service.DfuControlService.EXTRA_ERROR_CODE;
import static cc.calliope.mini.service.DfuControlService.EXTRA_ERROR_MESSAGE;

import static no.nordicsemi.android.dfu.DfuBaseService.EXTRA_DATA;


public class ProgressCollector extends ContextWrapper implements DefaultLifecycleObserver {
    private static final String TAG = "ProgressCollector";
    private final Context context;
    private ProgressListener listener;

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) {
                return;
            }

            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            // Take action depending on new bond state
            if (action.equals(ACTION_BOND_STATE_CHANGED)) {
                final int bondState = intent.getIntExtra(EXTRA_BOND_STATE, ERROR);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, ERROR);
                listener.onBluetoothBondingStateChanged(device, bondState, previousBondState);
            }
        }
    };

    private final BroadcastReceiver dfuServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case DfuService.BROADCAST_PROGRESS -> {
                    int extra = intent.getIntExtra(EXTRA_DATA, 0);
                    listener.onProgressUpdate(extra);
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
                    listener.onError(code, message);
                }
            }
        }
    };

    private final BroadcastReceiver dfuControlServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case DfuControlService.BROADCAST_START ->
                        listener.onProgressUpdate(DfuService.PROGRESS_CONNECTING);
                case DfuControlService.BROADCAST_COMPLETED -> {
                    int boardVersion = intent.getIntExtra(EXTRA_BOARD_VERSION, UNIDENTIFIED);
                    listener.onHardwareVersionReceived(boardVersion);
                }
                case DfuControlService.BROADCAST_CONNECTION_FAILED -> listener.onConnectionFailed();
                case DfuControlService.BROADCAST_ERROR -> {
                    int code = intent.getIntExtra(EXTRA_ERROR_CODE, -1);
                    String message = intent.getStringExtra(EXTRA_ERROR_MESSAGE);
                    listener.onError(code, message);
                }
            }
        }
    };

    private final BroadcastReceiver partialFlashingServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case PartialFlashingBaseService.BROADCAST_PROGRESS -> {
                    int percent = intent.getIntExtra(PartialFlashingBaseService.EXTRA_PROGRESS, 0);
                    listener.onProgressUpdate(percent);
                }
                case PartialFlashingBaseService.BROADCAST_START ->
                        listener.onProgressUpdate(DfuService.PROGRESS_STARTING);
                case PartialFlashingBaseService.BROADCAST_COMPLETE ->
                        listener.onProgressUpdate(DfuService.PROGRESS_COMPLETED);
                case PartialFlashingBaseService.BROADCAST_PF_FAILED ->
                        listener.onError(-1, "Partial Flashing FAILED");
                case PartialFlashingBaseService.BROADCAST_PF_ATTEMPT_DFU -> listener.onDfuAttempt();
            }
        }
    };

    public ProgressCollector(Context context) {
        super(context);
        this.context = context;
        if (context instanceof ProgressListener) {
            this.listener = (ProgressListener) context;
        }
    }

    public void registerProgressListener(ProgressListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        if (owner instanceof FlashingService)
            Utils.log(TAG, "onCreate: " + owner);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        if (owner instanceof FlashingService)
            Utils.log(TAG, "onStart: " + owner);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        registerReceivers();
        if (owner instanceof FlashingService)
            Utils.log(TAG, "onResume: " + owner);
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        unregisterReceivers();
        if (owner instanceof FlashingService)
            Utils.log(TAG, "onPause: " + owner);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        if (owner instanceof FlashingService)
            Utils.log(TAG, "onStop: " + owner);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        if (owner instanceof FlashingService)
            Utils.log(TAG, "onDestroy: " + owner);
    }

    public void registerReceivers() {
        if (listener == null) {
            return;
        }
        //BondStateReceiver
        IntentFilter bondStateFilter = new IntentFilter();
        bondStateFilter.addAction(ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondStateReceiver, bondStateFilter);

        //DfuService
        IntentFilter dfuServiceFilter = new IntentFilter();
        dfuServiceFilter.addAction(DfuService.BROADCAST_PROGRESS);
        dfuServiceFilter.addAction(DfuService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(context).registerReceiver(dfuServiceReceiver, dfuServiceFilter);

        //DfuControlService
        IntentFilter dfuControlServiceFilter = new IntentFilter();
        dfuControlServiceFilter.addAction(DfuControlService.BROADCAST_START);
        dfuControlServiceFilter.addAction(DfuControlService.BROADCAST_COMPLETED);
        dfuControlServiceFilter.addAction(DfuControlService.BROADCAST_CONNECTION_FAILED);
        dfuControlServiceFilter.addAction(DfuControlService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(context).registerReceiver(dfuControlServiceReceiver, dfuControlServiceFilter);

        //PartialFlashingService
        IntentFilter partialFlashingServiceFilter = new IntentFilter();
        partialFlashingServiceFilter.addAction(PartialFlashingBaseService.BROADCAST_PROGRESS);
        partialFlashingServiceFilter.addAction(PartialFlashingBaseService.BROADCAST_START);
        partialFlashingServiceFilter.addAction(PartialFlashingBaseService.BROADCAST_COMPLETE);
        partialFlashingServiceFilter.addAction(PartialFlashingBaseService.BROADCAST_PF_FAILED);
        partialFlashingServiceFilter.addAction(PartialFlashingBaseService.BROADCAST_PF_ATTEMPT_DFU);
        LocalBroadcastManager.getInstance(context).registerReceiver(partialFlashingServiceReceiver, partialFlashingServiceFilter);

    }

    public void unregisterReceivers() {
        unregisterReceiver(bondStateReceiver);
        LocalBroadcastManager.getInstance(context).unregisterReceiver(dfuServiceReceiver);
        LocalBroadcastManager.getInstance(context).unregisterReceiver(dfuControlServiceReceiver);
        LocalBroadcastManager.getInstance(context).unregisterReceiver(partialFlashingServiceReceiver);
    }
}
