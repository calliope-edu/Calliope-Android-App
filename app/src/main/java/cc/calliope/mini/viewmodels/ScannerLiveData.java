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

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.content.Context.BLUETOOTH_SERVICE;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import cc.calliope.mini.ExtendedBluetoothDevice;
import cc.calliope.mini.dialog.pattern.PatternEnum;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.support.v18.scanner.ScanResult;

/**
 * This class keeps the current list of discovered Bluetooth LE devices matching filter.
 * If a new device has been found it is added to the list and the LiveData i observers are
 * notified. If a packet from a device that's already in the list is found, the RSSI and name
 * are updated and observers are also notified.
 */

public class ScannerLiveData extends LiveData<ScannerLiveData> {
    private final List<ExtendedBluetoothDevice> devices = new ArrayList<>();
    private Float[] currentPattern = {0f, 0f, 0f, 0f, 0f};
    private Integer updatedDeviceIndex;
    private boolean scanningStarted;
    private boolean bluetoothEnabled;
    private boolean locationEnabled;

    /* package */ ScannerLiveData(final boolean bluetoothEnabled, final boolean locationEnabled) {
        this.scanningStarted = false;
        this.bluetoothEnabled = bluetoothEnabled;
        this.locationEnabled = locationEnabled;
        postValue(this);
    }

    /* package */ void refresh() {
        postValue(this);
    }

    /* package */ void scanningStarted() {
        scanningStarted = true;
        postValue(this);
    }

    /* package */ void scanningStopped() {
        scanningStarted = false;
        postValue(this);
    }

    /* package */ void bluetoothEnabled() {
        bluetoothEnabled = true;
        postValue(this);
    }

    /* package */ void bluetoothDisabled() {
        bluetoothEnabled = false;
        updatedDeviceIndex = null;
        devices.clear();
        postValue(this);
    }

    /* package */ void setLocationEnabled(final boolean enabled) {
        locationEnabled = enabled;
        postValue(this);
    }

    public Float[] getCurrentPattern() {
        return currentPattern;
    }

    void setCurrentPattern(Float[] pattern) {
        currentPattern = pattern;
        postValue(this);
    }

    void createBond() {
        ExtendedBluetoothDevice extendedDevice = getCurrentDevice();
        if (extendedDevice != null) {
            BluetoothDevice device = extendedDevice.getDevice();
            Utils.log(Log.ASSERT, "BOUND", "Device: " + device.getName());
            int bondState = device.getBondState();
            Utils.log(Log.ASSERT, "BOUND", "bondState: " + bondState);
            if (bondState == BOND_BONDED) {
                deleteBond(device);
            }
            //TODO Костить
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            device.createBond();
        }
    }

    private void deleteBond(BluetoothDevice device) {
        try {
            Method method = device.getClass().getMethod("removeBond", (Class[]) null);
            method.invoke(device, (Object[]) null);
        } catch (NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException e) {
            Log.e("ERROR", e.toString());
        }
    }

    public ExtendedBluetoothDevice getCurrentDevice() {
        for (ExtendedBluetoothDevice device : devices) {
            int coincide = 0;
            for (int i = 0; i < 5; i++) {
                char character = device.getPattern().charAt(i);
                String patternColumn = PatternEnum.forCode(currentPattern[i]).toString();
                if (patternColumn.contains(String.valueOf(character))) {
                    coincide++;
                }
            }
            if (coincide == 5) {
                return device;
            }
        }
        return null;
    }

    void devicesDiscovered(final List<ScanResult> results) {
        if (results != null) {
            devices.clear();
            for (ScanResult result : results) {
                deviceDiscovered(result);
            }
            postValue(this);
        }
    }

    /* package */ void deviceDiscovered(final ScanResult result) {
        if (result.getScanRecord() != null) {
            String deviceName = result.getScanRecord().getDeviceName();
            String address = result.getDevice().getAddress();

//            Utils.log(Log.ASSERT, "SCAN", "Device name: " + deviceName + ", address: " + address);
            if (deviceName != null) {
//                System.out.println("Found Device: " + deviceName);

                Pattern p = Pattern.compile("[a-zA-Z :]+\\u005b(([A-Z]){5})\\u005d");
                Matcher m = p.matcher(deviceName.toUpperCase());

                if (m.matches()) {

                    ExtendedBluetoothDevice device;

                    final int index = indexOf(result);
                    if (index == -1) {
                        device = new ExtendedBluetoothDevice(result);
                        devices.add(device);
                        updatedDeviceIndex = null;
                    } else {
                        device = devices.get(index);
                        updatedDeviceIndex = index;
                        // Update RSSI and name
                        device.setRssi(result.getRssi());
                        device.setName(result.getScanRecord().getDeviceName());
                        device.setPattern(m.group(1));
                        device.setRecentUpdate(new Date().getTime());
                    }
                    postValue(this);
                }
            }
        }
    }

    /**
     * Returns the list of devices.
     *
     * @return current list of devices discovered
     */
    @NonNull
    public List<ExtendedBluetoothDevice> getDevices() {
        return devices;
    }

    /**
     * Returns null if a new device was added, or an index of the updated device.
     */
    @Nullable
    public Integer getUpdatedDeviceIndex() {
        final Integer i = updatedDeviceIndex;
        updatedDeviceIndex = null;
        return i;
    }

    /**
     * Returns whether the list is empty.
     */
    public boolean isEmpty() {
        return devices.isEmpty();
    }

    /**
     * Returns whether scanning is in progress.
     */
    public boolean isScanning() {
        return scanningStarted;
    }

    /**
     * Returns whether Bluetooth adapter is enabled.
     */
    public boolean isBluetoothEnabled() {
        return bluetoothEnabled;
    }

    /**
     * Returns whether Location is enabled.
     */
    public boolean isLocationEnabled() {
        return locationEnabled;
    }

    /**
     * Finds the index of existing devices on the scan results list.
     *
     * @param result scan result
     * @return index of -1 if not found
     */
    private int indexOf(final ScanResult result) {
        int i = 0;
        for (final ExtendedBluetoothDevice device : devices) {
            if (device.matches(result))
                return i;
            i++;
        }
        return -1;
    }
}
