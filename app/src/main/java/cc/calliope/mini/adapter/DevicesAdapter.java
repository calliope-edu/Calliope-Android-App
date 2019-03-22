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

package cc.calliope.mini.adapter;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import cc.calliope.mini.ScannerActivity;
import cc.calliope.mini.R;
import cc.calliope.mini.viewmodels.ScannerLiveData;
//import cc.calliope.mini.R;

@SuppressWarnings("unused")
public class DevicesAdapter extends RecyclerView.Adapter<DevicesAdapter.ViewHolder> {
	private final ScannerActivity mContext;
	private final List<ExtendedBluetoothDevice> mDevices;
	private OnItemClickListener mOnItemClickListener;

	@FunctionalInterface
	public interface OnItemClickListener {
		void onItemClick(final ExtendedBluetoothDevice device);
	}

	public void setOnItemClickListener(final Context context) {
		mOnItemClickListener = (OnItemClickListener) context;
	}

	public DevicesAdapter(final ScannerActivity activity, final ScannerLiveData scannerLiveData) {
		mContext = activity;
		mDevices = scannerLiveData.getDevices();
		scannerLiveData.observe(activity, devices -> {
			final Integer i = devices.getUpdatedDeviceIndex();
			if (i != null)
				notifyItemChanged(i);
			else
				notifyDataSetChanged();
		});
	}

//	public int getDevicePattern(char character) {
//
//		if ("ZU".contains(String.valueOf(character)))
//		return R.drawable.pattern1;
//		if ("VO".contains(String.valueOf(character)))
//			return R.drawable.pattern2;
//		if ("GI".contains(String.valueOf(character)))
//		return R.drawable.pattern3;
//		if ("PE".contains(String.valueOf(character)))
//			return R.drawable.pattern4;
//		if ("TA".contains(String.valueOf(character)))
//			return R.drawable.pattern5;
//		else
//			return R.drawable.pattern0;
//	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
		final View layoutView = LayoutInflater.from(mContext).inflate(R.layout.device_item, parent, false);
		return new ViewHolder(layoutView);
	}

	@Override
	public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
		final ExtendedBluetoothDevice device = mDevices.get(position);
		final String deviceName = device.getName();
		final String devicePattern = device.getPattern();

		if (!TextUtils.isEmpty(deviceName))
			holder.deviceName.setText(deviceName);
		else
			holder.deviceName.setText(R.string.unknown_device);
		holder.deviceAddress.setText(device.getAddress());
		final int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
		holder.rssi.setImageLevel(rssiPercent);
		holder.pattern1.setImageResource(device.getDevicePattern(0));
		holder.pattern2.setImageResource(device.getDevicePattern(1));
		holder.pattern3.setImageResource(device.getDevicePattern(2));
		holder.pattern4.setImageResource(device.getDevicePattern(3));
		holder.pattern5.setImageResource(device.getDevicePattern(4));
	}

	@Override
	public long getItemId(final int position) {
		return position;
	}

	@Override
	public int getItemCount() {
		return mDevices.size();
	}

	public boolean isEmpty() {
		return getItemCount() == 0;
	}


	final class ViewHolder extends RecyclerView.ViewHolder {
		@BindView(R.id.device_address) TextView deviceAddress;
		@BindView(R.id.device_name) TextView deviceName;
		@BindView(R.id.rssi) ImageView rssi;
		@BindView(R.id.pattern1) ImageView pattern1;
		@BindView(R.id.pattern2) ImageView pattern2;
		@BindView(R.id.pattern3) ImageView pattern3;
		@BindView(R.id.pattern4) ImageView pattern4;
		@BindView(R.id.pattern5) ImageView pattern5;

		private ViewHolder(final View view) {
			super(view);
			ButterKnife.bind(this, view);

			view.findViewById(R.id.device_container).setOnClickListener(v -> {
				if (mOnItemClickListener != null) {
					mOnItemClickListener.onItemClick(mDevices.get(getAdapterPosition()));
				}
			});
		}
	}
}
