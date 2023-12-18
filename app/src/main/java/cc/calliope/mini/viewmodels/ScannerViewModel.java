/*
 * Copyright (c) 2015, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package cc.calliope.mini.viewmodels;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.preference.PreferenceManager;
import cc.calliope.mini.App;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.Version;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

import static cc.calliope.mini.App.APP_STATE_FLASHING;

public class ScannerViewModel extends AndroidViewModel {

    // For checking the availability of the device.
    // If there is no one device in the bluetooth visibility range callback not working.
    private Timer timer;
    private static final int REFRESH_PERIOD = 3000;

    /**
     * MutableLiveData containing the scanner state to notify MainActivity.
     */
    private final ScannerLiveData mScannerLiveData;

    public ScannerLiveData getScannerState() {
        return mScannerLiveData;
    }

    public ScannerViewModel(final Application application) {
        super(application);

        mScannerLiveData = new ScannerLiveData(Utils.isBluetoothEnabled(),
                Utils.isLocationEnabled(application) || Version.VERSION_S_AND_NEWER);
        registerBroadcastReceivers(application);
        loadPattern();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        Application application = getApplication();

        application.unregisterReceiver(mBluetoothStateBroadcastReceiver);

        if (Version.VERSION_M_AND_NEWER) {
            application.unregisterReceiver(mLocationProviderChangedReceiver);
        }
    }

    public void refresh() {
        mScannerLiveData.refresh();
    }

    /**
     * Start scanning for Bluetooth devices.
     */
    public void startScan() {
        Log.e("SCANNER", "### " + Thread.currentThread().getId() + " # " + "startScan()");
        boolean isFlashing = ((App) getApplication()).getAppState() == APP_STATE_FLASHING;
        if (mScannerLiveData.isScanning() || !mScannerLiveData.isBluetoothEnabled() || isFlashing) {
            return;
        }

        startTimer();

        // Scanning settings
        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                // Refresh the devices list every 5000 ms (5 sec)
                .setReportDelay(0)
                // Hardware filtering has some issues on selected devices
                .setUseHardwareFilteringIfSupported(false)
                // Samsung S6 and S6 Edge report equal value of RSSI for all devices. In this app we ignore the RSSI.
                /*.setUseHardwareBatchingIfSupported(false)*/
                .build();

        // Let's use the filter to scan only for Blinky devices
//		final ParcelUuid uuid = new ParcelUuid(LBS_UUID_SERVICE);
        final List<ScanFilter> filters = new ArrayList<>();
//		filters.add(new ScanFilter.Builder().setServiceUuid(uuid).build());
//		filters.add(new ScanFilter.Builder().setDeviceName(Pattern.compile("\\u005b([a-z]){5}\\u005d")).build());

        final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.startScan(filters, settings, scanCallback);
        mScannerLiveData.scanningStarted();
    }

    /**
     * stop scanning for bluetooth devices.
     */
    public void stopScan() {
        stopTimer();
        Log.e("SCANNER", "### " + Thread.currentThread().getId() + " # " + "stopScan()");
        final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.stopScan(scanCallback);
        mScannerLiveData.scanningStopped();
        savePattern();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, @NonNull final ScanResult result) {

            //TODO Are we need it?
//			if (Utils.isLocationRequired(getApplication()) && !Utils.isLocationEnabled(getApplication())) {
//                Utils.markLocationNotRequired(getApplication());
//            }

            mScannerLiveData.deviceDiscovered(result);
        }

        @Override
        public void onBatchScanResults(@NonNull final List<ScanResult> results) {
            // If the packet has been obtained while Location was disabled, mark Location as not required
//            mScannerLiveData.devicesDiscovered(results);
        }

        @Override
        public void onScanFailed(final int errorCode) {
            // TODO This should be handled
            Log.e("SCANNER", "### " + Thread.currentThread().getId() + " # " + "onScanFailed(), errorCode: " + errorCode);
            stopScan();
//            mScannerLiveData.scanningStopped();
        }
    };

    public void setCurrentPattern(Float[] pattern) {
        mScannerLiveData.setCurrentPattern(pattern);
    }

    public void createBond(){
        mScannerLiveData.createBond();
    }

    /**
     * Register for required broadcast receivers.
     */
    private void registerBroadcastReceivers(final Application application) {
        application.registerReceiver(mBluetoothStateBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        if (Version.VERSION_M_AND_NEWER) {
            application.registerReceiver(mLocationProviderChangedReceiver, new IntentFilter(LocationManager.MODE_CHANGED_ACTION));
        }
    }

    /**
     * Broadcast receiver to monitor the changes in the location provider
     */
    private final BroadcastReceiver mLocationProviderChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final boolean enabled = Utils.isLocationEnabled(context);
            mScannerLiveData.setLocationEnabled(enabled);
        }
    };

    /**
     * Broadcast receiver to monitor the changes in the bluetooth adapter
     */
    private final BroadcastReceiver mBluetoothStateBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
            final int previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    startScan();
                    mScannerLiveData.bluetoothEnabled();
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                case BluetoothAdapter.STATE_OFF:
                    if (previousState != BluetoothAdapter.STATE_TURNING_OFF && previousState != BluetoothAdapter.STATE_OFF) {
                        stopScan();
                        mScannerLiveData.bluetoothDisabled();
                    }
                    break;
            }
        }
    };

    public void savePattern() {
        Float[] currentPattern = mScannerLiveData.getCurrentPattern();
        if (currentPattern != null) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplication());
            SharedPreferences.Editor edit = sharedPreferences.edit();
            for (int i = 0; i < 5; i++) {
                edit.putFloat("PATTERN_" + i, currentPattern[i]);
            }
            edit.apply();
        }
    }

    public void loadPattern() {
        Float[] currentPattern = {0f, 0f, 0f, 0f, 0f};
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplication());
        for (int i = 0; i < 5; i++) {
            currentPattern[i] = preferences.getFloat("PATTERN_" + i, 0f);
        }
        mScannerLiveData.setCurrentPattern(currentPattern);
    }

    public void startTimer() {
        stopTimer();
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                refresh();
//                Log.w("Timer", "### " + Thread.currentThread().getId() + " # " + "scannerViewModel.refresh()");
            }
        }, 0, REFRESH_PERIOD);
        Log.d("Timer", "### " + Thread.currentThread().getId() + " # " + "timer: " + timer);
    }

    public void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
        }
    }
}
