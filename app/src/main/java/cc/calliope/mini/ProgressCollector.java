package cc.calliope.mini;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import cc.calliope.mini.core.service.DfuService;
import cc.calliope.mini.core.service.LegacyDfuService;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.error.GattError;

import static android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED;
import static android.bluetooth.BluetoothDevice.ERROR;
import static android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE;

import static no.nordicsemi.android.dfu.DfuBaseService.EXTRA_DATA;


public class ProgressCollector extends ContextWrapper implements DefaultLifecycleObserver {
    private final Context context;
    private ProgressListener listener;

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
                case LegacyDfuService.BROADCAST_COMPLETED -> listener.onDfuControlComplete();
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
        registerReceivers();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        unregisterReceivers();
    }

    public void registerReceivers() {
        if (listener == null) {
            return;
        }

        //DfuService
        IntentFilter dfuServiceFilter = new IntentFilter();
        dfuServiceFilter.addAction(DfuService.BROADCAST_PROGRESS);
        dfuServiceFilter.addAction(DfuService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(context).registerReceiver(dfuServiceReceiver, dfuServiceFilter);

        //DfuControlService
        IntentFilter dfuControlServiceFilter = new IntentFilter();
        dfuControlServiceFilter.addAction(LegacyDfuService.BROADCAST_COMPLETED);
        LocalBroadcastManager.getInstance(context).registerReceiver(dfuControlServiceReceiver, dfuControlServiceFilter);
    }

    public void unregisterReceivers() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(dfuServiceReceiver);
        LocalBroadcastManager.getInstance(context).unregisterReceiver(dfuControlServiceReceiver);
    }
}
