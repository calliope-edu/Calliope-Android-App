/*
 * Copyright (c) 2015, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package cc.calliope.mini.profile;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.Deque;
import java.util.LinkedList;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.Request;
import no.nordicsemi.android.log.LogContract;

public class BlinkyManager extends BleManager<BlinkyManagerCallbacks> {
	/** MINI Service UUID. */
	public final static UUID LBS_UUID_SERVICE = UUID.fromString("E95D93B0-251D-470A-A062-FA1922DFA9A8");
	/** BUTTON characteristic UUID. DOESNT EXIST */
	private final static UUID LBS_UUID_BUTTON_CHAR = UUID.fromString("E95D93B1-251D-470A-A062-FA1922DFA9A8");
	/** (LED) OPEN FIRMWARE FLASH MODE CHARACTERISTICS UUID. */
	private final static UUID LBS_UUID_LED_CHAR = UUID.fromString("E95D93B1-251D-470A-A062-FA1922DFA9A8");

	private BluetoothGattCharacteristic mButtonCharacteristic, mLedCharacteristic;

	public BlinkyManager(final Context context) {
		super(context);
	}

	@NonNull
	@Override
	protected BleManagerGattCallback getGattCallback() {
		return mGattCallback;
	}

	@Override
	protected boolean shouldAutoConnect() {
		// If you want to connect to the device using autoConnect flag = true, return true here.
		// Read the documentation of this method.
//		return super.shouldAutoConnect();
        return true;
	}

	/**
	 * BluetoothGatt callbacks for connection/disconnection, service discovery, receiving indication, etc
	 */
	private final BleManagerGattCallback mGattCallback = new BleManagerGattCallback() {

		@Override
		protected Deque<Request> initGatt(final BluetoothGatt gatt) {
			final LinkedList<Request> requests = new LinkedList<>();
			requests.push(Request.newReadRequest(mLedCharacteristic));
			requests.push(Request.newReadRequest(mButtonCharacteristic));
			requests.push(Request.newEnableNotificationsRequest(mButtonCharacteristic));
			return requests;
		}

		@Override
		public boolean isRequiredServiceSupported(final BluetoothGatt gatt) {
			final BluetoothGattService service = gatt.getService(LBS_UUID_SERVICE);
			if (service != null) {
				mButtonCharacteristic = service.getCharacteristic(LBS_UUID_BUTTON_CHAR);
				mLedCharacteristic = service.getCharacteristic(LBS_UUID_LED_CHAR);
			}
            Log.i("OWN", "CHARTEST: " + mLedCharacteristic);

			boolean writeRequest = false;
			if (mLedCharacteristic != null) {
				final int rxProperties = mLedCharacteristic.getProperties();
				writeRequest = (rxProperties & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0;
			}

			return mButtonCharacteristic != null && mLedCharacteristic != null && writeRequest;
		}

		@Override
		protected void onDeviceDisconnected() {
			mButtonCharacteristic = null;
			mLedCharacteristic = null;
		}

		@Override
		protected void onCharacteristicRead(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
			final int data = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
			if (characteristic == mLedCharacteristic) {
				final boolean ledOn = data == 0x01;
				log(LogContract.Log.Level.APPLICATION, "LED " + (ledOn ? "ON" : "OFF"));
				mCallbacks.onDataSent(ledOn);
			} else {
				final boolean buttonPressed = data == 0x01;
				log(LogContract.Log.Level.APPLICATION, "Button " + (buttonPressed ? "pressed" : "released"));
				mCallbacks.onDataReceived(buttonPressed);
			}
		}

		@Override
		public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
			// This method is only called for LED characteristic
			final int data = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
			final boolean ledOn = data == 0x01;
			log(LogContract.Log.Level.APPLICATION, "LED " + (ledOn ? "ON" : "OFF"));
			mCallbacks.onDataSent(ledOn);
		}

		@Override
		public void onCharacteristicNotified(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
			// This method is only called for Button characteristic
			final int data = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
			final boolean buttonPressed = data == 0x01;
			log(LogContract.Log.Level.APPLICATION, "Button " + (buttonPressed ? "pressed" : "released"));
			mCallbacks.onDataReceived(buttonPressed);
		}
	};

	public void send() {
		// Are we connected?
		Log.i("OWN", "DFU Characteristics: " + mLedCharacteristic);

		if (mLedCharacteristic == null)
			return;

		final byte[] command = new byte[] {(byte) (0x01)};
		Log.i("OWN", "turning on DFU");
		writeCharacteristic(mLedCharacteristic, command);
	}
}
