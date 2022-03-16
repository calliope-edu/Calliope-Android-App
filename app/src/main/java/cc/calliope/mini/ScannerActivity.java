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

package cc.calliope.mini;

import android.Manifest;
import android.arch.lifecycle.ViewModelProviders;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import cc.calliope.mini.adapter.DevicesAdapter;
import cc.calliope.mini.adapter.ExtendedBluetoothDevice;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.viewmodels.ScannerLiveData;
import cc.calliope.mini.viewmodels.ScannerViewModel;

public class ScannerActivity extends AppCompatActivity implements DevicesAdapter.OnItemClickListener {
	private static final int REQUEST_ACCESS_FINE_LOCATION = 1022; // random number
	private static final int REQUEST_ACCESS_BLUETOOTH_SCAN = 1023; // random number



	private ScannerViewModel mScannerViewModel;
	private AnimationDrawable pairAnimation;

	@BindView(R.id.state_scanning) View mScanningView;
	@BindView(R.id.no_devices)View mEmptyView;
	@BindView(R.id.no_location_permission) View mNoLocationPermissionView;
	@BindView(R.id.action_grant_location_permission) Button mGrantPermissionButton;
	@BindView(R.id.action_permission_settings) Button mPermissionSettingsButton;
	@BindView(R.id.no_location)	View mNoLocationView;
	@BindView(R.id.bluetooth_off) View mNoBluetoothView;
    @BindView(R.id.pairAnimationLayout) View mPairAnimation;

	@Override
	protected void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_scanner);
		ButterKnife.bind(this);

//		final Toolbar toolbar = findViewById(R.id.toolbar);
//		setSupportActionBar(toolbar);
//		getSupportActionBar().setTitle(R.string.app_name);

		// Create view model containing utility methods for scanning
		mScannerViewModel = ViewModelProviders.of(this).get(ScannerViewModel.class);
		mScannerViewModel.getScannerState().observe(this, this::startScan);

		// Configure the recycler view
		final RecyclerView recyclerView = findViewById(R.id.recycler_view_ble_devices);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		final DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
		recyclerView.addItemDecoration(dividerItemDecoration);
		((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
		final DevicesAdapter adapter = new DevicesAdapter(this, mScannerViewModel.getScannerState());
		adapter.setOnItemClickListener(this);
		recyclerView.setAdapter(adapter);

        ImageView rocketImage = findViewById(R.id.pair_animation);
        rocketImage.setBackgroundResource(R.drawable.pair_animation);
        pairAnimation = (AnimationDrawable) rocketImage.getBackground();
	}

    @Override
    protected void onStart() {
		super.onStart();
		pairAnimation.start();
    }

	@Override
	protected void onStop() {
		super.onStop();
		stopScan();
	}

	@Override
	public void onItemClick(final ExtendedBluetoothDevice device) {
		final Intent controlMiniIntent = new Intent(this, cc.calliope.mini.MainActivity.class);
		Log.i("DEVICE", device+"");
		Log.i("DEVICE", device.getName()+"");
		Log.i("DEVICE", device.getPattern()+"");

		controlMiniIntent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);

		startActivity(controlMiniIntent);
	}

//	private void pairDevice(BluetoothDevice device) {
//		try {
//			Log.d("PAIRING", "Start Pairing...");
//
//			//waitingForBonding = true;
//
//			Method m = device.getClass()
//					.getMethod("createBond", (Class[]) null);
//			m.invoke(device, (Object[]) null);
//
//			Log.d("PAIRING", "Pairing finished.");
//		} catch (Exception e) {
//			Log.e("PAIRING", e.getMessage());
//		}
//	}
//
//	private void unpairDevice(BluetoothDevice device) {
//		try {
//			Method m = device.getClass()
//					.getMethod("removeBond", (Class[]) null);
//			m.invoke(device, (Object[]) null);
//		} catch (Exception e) {
//			Log.e("PAIRING", e.getMessage());
//		}


	@Override
	public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		switch (requestCode) {
			case REQUEST_ACCESS_FINE_LOCATION:
				mScannerViewModel.refresh();
				break;
		}
	}

	@OnClick(R.id.action_enable_location)
	public void onEnableLocationClicked() {
		final Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
		startActivity(intent);
	}

	@OnClick(R.id.action_enable_bluetooth)
	public void onEnableBluetoothClicked() {
		final Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		startActivity(enableIntent);
	}

	private static final String[] BLE_PERMISSIONS = new String[]{
			Manifest.permission.ACCESS_COARSE_LOCATION,
			Manifest.permission.ACCESS_FINE_LOCATION
	};

	private static final String[] ANDROID_12_BLE_PERMISSIONS = new String[]{
			Manifest.permission.BLUETOOTH_SCAN,
			Manifest.permission.BLUETOOTH_CONNECT,
			Manifest.permission.ACCESS_FINE_LOCATION
	};

	@OnClick(R.id.action_grant_location_permission)
	public void onGrantLocationPermissionClicked() {
		Utils.markLocationPermissionRequested(this);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			Log.i("PERMISSIONS", "12");
			ActivityCompat.requestPermissions(this, ANDROID_12_BLE_PERMISSIONS, REQUEST_ACCESS_BLUETOOTH_SCAN);
		} else {
			Log.i("PERMISSIONS", "LEGACY");
			ActivityCompat.requestPermissions(this, BLE_PERMISSIONS, REQUEST_ACCESS_FINE_LOCATION);
		}
	}

	@OnClick(R.id.action_permission_settings)
	public void onPermissionSettingsClicked() {
		final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
		intent.setData(Uri.fromParts("package", getPackageName(), null));
		startActivity(intent);
	}

	/**
	 * Start scanning for Bluetooth devices or displays a message based on the scanner state.
	 */
	private void startScan(final ScannerLiveData state) {
		Log.v("Scan Permission: ", Utils.isBluetoothScanPermissionsGranted(this)+"");
		Log.v("Location Permission: ", Utils.isLocationPermissionsGranted(this)+"");
		Log.v("Version", Build.VERSION.SDK_INT+"");
		// First, check the Location permission. This is required on Marshmallow onwards in order to scan for Bluetooth LE devices.
		if (Utils.isLocationPermissionsGranted(this)
		&& (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Utils.isBluetoothScanPermissionsGranted(this))
		) {
			mNoLocationPermissionView.setVisibility(View.GONE);

			// Bluetooth must be enabled
			if (state.isBluetoothEnabled()) {
				mNoBluetoothView.setVisibility(View.GONE);

				// We are now OK to start scanning
				mScannerViewModel.startScan();
				mScanningView.setVisibility(View.VISIBLE);

                mPairAnimation.setVisibility(View.VISIBLE);

				if (state.isEmpty()) {
					mEmptyView.setVisibility(View.VISIBLE);

					if (!Utils.isLocationRequired(this) || Utils.isLocationEnabled(this)) {
						mNoLocationView.setVisibility(View.INVISIBLE);
					} else {
						mNoLocationView.setVisibility(View.VISIBLE);
					}
				} else {
					mEmptyView.setVisibility(View.GONE);
				}
			} else {
				mNoBluetoothView.setVisibility(View.VISIBLE);
				mScanningView.setVisibility(View.INVISIBLE);
				mEmptyView.setVisibility(View.GONE);
			}
		} else {
			mNoLocationPermissionView.setVisibility(View.VISIBLE);
			mNoBluetoothView.setVisibility(View.GONE);
			mScanningView.setVisibility(View.INVISIBLE);
			mEmptyView.setVisibility(View.GONE);

			final boolean deniedForever = Utils.isLocationPermissionDeniedForever(this);
			mGrantPermissionButton.setVisibility(deniedForever ? View.GONE : View.VISIBLE);
			mPermissionSettingsButton.setVisibility(deniedForever ? View.VISIBLE : View.GONE);
		}
	}

	/**
	 * stop scanning for bluetooth devices.
	 */
	private void stopScan() {
		mScannerViewModel.stopScan();
	}
}
