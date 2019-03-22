/*
 * Copyright (c) 2010 - 2017, Nordic Semiconductor ASA
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form, except as embedded into a Nordic
 *    Semiconductor ASA integrated circuit in a product or a software update for
 *    such product, must reproduce the above copyright notice, this list of
 *    conditions and the following disclaimer in the documentation and/or other
 *    materials provided with the distribution.
 *
 * 3. Neither the name of Nordic Semiconductor ASA nor the names of its
 *    contributors may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 *
 * 4. This software, with or without modification, must only be used with a
 *    Nordic Semiconductor ASA integrated circuit.
 *
 * 5. Any software provided in binary form under this license must not be reverse
 *    engineered, decompiled, modified and/or disassembled.
 *
 * THIS SOFTWARE IS PROVIDED BY NORDIC SEMICONDUCTOR ASA "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY, NONINFRINGEMENT, AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NORDIC SEMICONDUCTOR ASA OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package cc.calliope.mini.adapter;

import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;

import cc.calliope.mini.R;
import no.nordicsemi.android.support.v18.scanner.ScanResult;

public class ExtendedBluetoothDevice implements Parcelable {
	private final BluetoothDevice device;
	private String name;
	private String pattern;
	private int rssi;

	public ExtendedBluetoothDevice(final ScanResult scanResult) {
		this.device = scanResult.getDevice();
		this.name = scanResult.getScanRecord().getDeviceName();
		this.pattern = "00000";
		this.rssi = scanResult.getRssi();
	}

	public BluetoothDevice getDevice() {
		return device;
	}

	public String getAddress() {
		return device.getAddress();
	}

	public String getName() {
		return name;
	}

	public String getPattern() {
		return pattern;
	}

	public int getDevicePattern(int characterPosition) {

		try {
			char character = pattern.charAt(characterPosition);

			if ("ZU".contains(String.valueOf(character)))
				return R.drawable.pattern1;
			if ("VO".contains(String.valueOf(character)))
				return R.drawable.pattern2;
			if ("GI".contains(String.valueOf(character)))
				return R.drawable.pattern3;
			if ("PE".contains(String.valueOf(character)))
				return R.drawable.pattern4;
			if ("TA".contains(String.valueOf(character)))
				return R.drawable.pattern5;
			else
				return R.drawable.pattern0;
		} catch (Exception e) {
			return R.drawable.pattern0;
		}
	}

	public void setName(final String name) {
		this.name = name;
	}

	public void setPattern(final String pattern) {
		this.pattern = pattern;
	}

	public int getRssi() {
		return rssi;
	}

	public void setRssi(final int rssi) {
		this.rssi = rssi;
	}

	public boolean matches(final ScanResult scanResult) {
		return device.getAddress().equals(scanResult.getDevice().getAddress());
	}

	@Override
	public boolean equals(final Object o) {
		if (o instanceof ExtendedBluetoothDevice) {
			final ExtendedBluetoothDevice that = (ExtendedBluetoothDevice) o;
			return device.getAddress().equals(that.device.getAddress());
		}
		return super.equals(o);
	}

	// Parcelable implementation

	private ExtendedBluetoothDevice(final Parcel in) {
		this.device = in.readParcelable(BluetoothDevice.class.getClassLoader());
		this.name = in.readString();
		this.pattern = in.readString();
		this.rssi = in.readInt();
	}

	@Override
	public void writeToParcel(final Parcel parcel, final int flags) {
		parcel.writeParcelable(device, flags);
		parcel.writeString(name);
		parcel.writeString(pattern);
		parcel.writeInt(rssi);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	public static final Creator<ExtendedBluetoothDevice> CREATOR = new Creator<ExtendedBluetoothDevice>() {
		@Override
		public ExtendedBluetoothDevice createFromParcel(final Parcel source) {
			return new ExtendedBluetoothDevice(source);
		}

		@Override
		public ExtendedBluetoothDevice[] newArray(final int size) {
			return new ExtendedBluetoothDevice[size];
		}
	};
}
