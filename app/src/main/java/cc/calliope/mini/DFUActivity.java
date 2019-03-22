package cc.calliope.mini;

import android.arch.lifecycle.ViewModelProviders;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Method;

import cc.calliope.mini.adapter.ExtendedBluetoothDevice;
import cc.calliope.mini.service.DfuService;
import cc.calliope.mini.viewmodels.BlinkyViewModel;
import no.nordicsemi.android.error.GattError;

public class DFUActivity extends AppCompatActivity {


    private DFUResultReceiver dfuResultReceiver;
    private static final String TAG = DFUActivity.class.getSimpleName();

    private int mActivityState;

    private String m_BinSizeStats = "0";
    private String m_MicroBitFirmware = "0.0";
    private long starttime;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dfu);

        final Intent intent = getIntent();
        final ExtendedBluetoothDevice device = intent.getParcelableExtra("cc.calliope.mini.EXTRA_DEVICE");

        Bundle extras = intent.getExtras();
        final String file = extras.getString("EXTRA_FILE");

        final String deviceName = device.getName();

        final BlinkyViewModel viewModel = ViewModelProviders.of(this).get(BlinkyViewModel.class);
        final TextView deviceInfo = findViewById(R.id.statusInfo);

//        unpairDevice(device.getDevice());
//        deviceInfo.setText("Repairing...");
//        pairDevice(device.getDevice());
        // TODO: https://stackoverflow.com/questions/19047995/programmatically-pair-bluetooth-device-without-the-user-entering-pin

//        viewModel.connect(device);
//        deviceInfo.setText("warte auf Mini... (A+B+Reset)");
//        viewModel.isDeviceReady().observe(this, deviceReady -> {
//            viewModel.startDFU();
            initiateFlashing();
//        });

    }



    private void pairDevice(BluetoothDevice device) {
        try {
            Log.d(TAG, "Start Pairing...");

            //waitingForBonding = true;

            Method m = device.getClass()
                    .getMethod("createBond", (Class[]) null);
            m.invoke(device, (Object[]) null);

            Log.d(TAG, "Pairing finished.");
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void unpairDevice(BluetoothDevice device) {
        try {
            Method m = device.getClass()
                    .getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }


    /**
     * Prepares for flashing process.
     * <p/>
     * <p>>Unregisters DFU receiver, sets activity state to the find device state,
     * registers callbacks requisite for flashing and starts flashing.</p>
     */
    protected void initiateFlashing() {
        if(dfuResultReceiver != null) {
            LocalBroadcastManager.getInstance(DFUActivity.this).unregisterReceiver(dfuResultReceiver);
            dfuResultReceiver = null;
        }
//        setActivityState(cc.calliope.mini.data.model.ui.FlashActivityState.FLASH_STATE_FIND_DEVICE);
        registerCallbacksForFlashing();
        startFlashing();
    }

    /**
     * Updates UI of current connection status and device name.
     */
    private void setConnectedDeviceText() {
        // TODO
            return;
    }


    /**
     * Creates and starts service to flash a program to a micro:bit board.
     */
    protected void startFlashing() {
//        logi(">>>>>>>>>>>>>>>>>>> startFlashing called  >>>>>>>>>>>>>>>>>>>  ");
        //Reset all stats value
//        m_BinSizeStats = "0";
//        m_MicroBitFirmware = "0.0";
//        m_HexFileSizeStats = FileUtils.getFileSize(mProgramToSend.filePath);

        final Intent intent = getIntent();
        final ExtendedBluetoothDevice device = intent.getParcelableExtra("cc.calliope.mini.EXTRA_DEVICE");

        Bundle extras = intent.getExtras();
        final String file = extras.getString("EXTRA_FILE");

//        MBApp application = MBApp.getApp();

//        final Intent service = new Intent(application, DfuService.class);
        final Intent service = new Intent(this, DfuService.class);

//        service.putExtra(DfuService.EXTRA_DEVICE_ADDRESS, currentMicrobit.mAddress);
//        service.putExtra(DfuService.EXTRA_DEVICE_NAME, currentMicrobit.mPattern);
//        service.putExtra(DfuService.EXTRA_DEVICE_PAIR_CODE, currentMicrobit.mPairingCode);
//        service.putExtra(DfuService.EXTRA_FILE_MIME_TYPE, DfuService.MIME_TYPE_OCTET_STREAM);
//        service.putExtra(DfuService.EXTRA_FILE_PATH, mProgramToSend.filePath); // a path or URI must be provided.
//        service.putExtra(DfuService.EXTRA_KEEP_BOND, false);
//        service.putExtra(DfuService.INTENT_REQUESTED_PHASE, 2);

        service.putExtra(DfuService.EXTRA_DEVICE_ADDRESS, device.getAddress());
        service.putExtra(DfuService.EXTRA_DEVICE_NAME, device.getName());
        service.putExtra(DfuService.EXTRA_DEVICE_PAIR_CODE, 0);
        service.putExtra(DfuService.EXTRA_FILE_MIME_TYPE, DfuService.MIME_TYPE_OCTET_STREAM);
        service.putExtra(DfuService.EXTRA_FILE_PATH, file); // a path or URI must be provided.
        service.putExtra(DfuService.EXTRA_KEEP_BOND, false);
        service.putExtra(DfuService.INTENT_REQUESTED_PHASE, 2);

        Log.i("DFUExtra", "mAddress: "+device.getAddress());
        Log.i("DFUExtra", "mPattern: "+device.getName());
        Log.i("DFUExtra", "mPairingCode: "+0);
        Log.i("DFUExtra", "MIME_TYPE_OCTET_STREAM: "+DfuService.MIME_TYPE_OCTET_STREAM);
        Log.i("DFUExtra", "filePath: "+file);

        Log.i("DFUExtra", "Start Flashing");

        startService(service);

    }

    /**
     * Registers callbacks that allows to handle flashing process
     * and react to flashing progress, errors and log some messages.
     */
    private void registerCallbacksForFlashing() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(DfuService.BROADCAST_PROGRESS);
        filter.addAction(DfuService.BROADCAST_ERROR);
        filter.addAction(DfuService.BROADCAST_LOG);
        dfuResultReceiver = new DFUResultReceiver();

        LocalBroadcastManager.getInstance(DFUActivity.this).registerReceiver(dfuResultReceiver, filter);
    }



    /**
     * Represents a broadcast receiver that allows to handle states of
     * flashing process.
     */
    class DFUResultReceiver extends BroadcastReceiver {

        private boolean isCompleted = false;
        private boolean inInit = false;
        private boolean inProgress = false;

//        private View.OnClickListener okFinishFlashingHandler = new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//            }
//        };

        long starttime = 0;
        long millis = 0;

        @Override
        public void onReceive(Context context, Intent intent) {

            final TextView deviceInfo = findViewById(R.id.statusInfo);
            final TextView timerText = findViewById(R.id.timerText);
            String message = "Broadcast intent detected " + intent.getAction();
//            logi("DFUResultReceiver.onReceive :: " + message);
            Log.e("DFUResultReceiver", " "+intent.getAction());
            if(intent.getAction().equals(DfuService.BROADCAST_LOG) || intent.getAction().equals(DfuService.BROADCAST_ERROR)) {
                String state = intent.getStringExtra(DfuService.EXTRA_DATA);
                Log.w("DFULogStatus", " "+state);
            } else if(intent.getAction().equals(DfuService.BROADCAST_PROGRESS)) {

                int state = intent.getIntExtra(DfuService.EXTRA_DATA, 0);
                Log.w("DFUResultStatus", " "+state);

                if(starttime == 0)
                    starttime = System.currentTimeMillis();
                millis = System.currentTimeMillis() - starttime;


                timerText.setText(millis + "");

                if(millis >= 20000 && state < 0 && state != -5) {
//                    timerText.setText(millis + " RESTART"); // TODO Restart activity OR dfu if it hängs
//                    this.recreate();
//                    Intent intentX = getIntent();
//                    finish();
//                    startActivity(intentX);
                }

                if(state < 0) {
//                    deviceInfo.setText("Initialisiere... "+state);
                    timerText.setText(R.string.info_text_uploading_init);

                    switch(state) {
                        case DfuService.PROGRESS_STARTING:
                            break;
                        case DfuService.PROGRESS_COMPLETED:
                            if(!isCompleted) {
                                dfuResultReceiver = null;
                            }
                            Log.e("OWN", "Fertig");
                            isCompleted = true;
                            inInit = false;
                            inProgress = false;

                            finish();

                            break;
                        case DfuService.PROGRESS_DISCONNECTING:
                            Log.e(TAG, "Progress disconnecting");
                            break;

                        case DfuService.PROGRESS_CONNECTING:
                            if((!inInit) && (!isCompleted)) {

//                                countOfReconnecting = 0;
                            }

                            inInit = true;
                            isCompleted = false;
                            break;
                        case DfuService.PROGRESS_VALIDATING:
                            break;
                        case DfuService.PROGRESS_WAITING_REBOOT:
                            break;
                        case DfuService.PROGRESS_VALIDATION_FAILED:
                            dfuResultReceiver = null;
                            break;
                        case DfuService.PROGRESS_ABORTED:
                            dfuResultReceiver = null;
//                            removeReconnectionRunnable();
                            break;
                        case DfuService.PROGRESS_SERVICE_NOT_FOUND:
                            Log.e(TAG, "service not found");
                            dfuResultReceiver = null;
//                            removeReconnectionRunnable();
                            break;

                    }
                } else if((state > 0) && (state < 100)) {
                    deviceInfo.setText(state+"%");
                    timerText.setText(R.string.info_text_uploading);
                    if(!inProgress) {

                        inProgress = true;

                    }

                }
            } else if(intent.getAction().equals(DfuService.BROADCAST_ERROR)) {
                deviceInfo.setText("Fehler!");
                int errorCode = intent.getIntExtra(DfuService.EXTRA_DATA, 0);

                if(errorCode == DfuService.ERROR_FILE_INVALID) {
//                    notAValidFlashHexFile = true;
                }

                String error_message = GattError.parse(errorCode);

                if(errorCode == DfuService.ERROR_FILE_INVALID) {
//                    error_message += getString(R.string.reset_microbit_because_of_hex_file_wrong);
                }
                deviceInfo.setText("Fehler! "+error_message);
                dfuResultReceiver = null;

//                removeReconnectionRunnable();
            } else if(intent.getAction().equals(DfuService.BROADCAST_LOG)) {
                //Only used for Stats at the moment
                String data;
                int logLevel = intent.getIntExtra(DfuService.EXTRA_LOG_LEVEL, 0);
                switch(logLevel) {
                    case DfuService.LOG_LEVEL_BINARY_SIZE:
                        data = intent.getStringExtra(DfuService.EXTRA_DATA);
                        m_BinSizeStats = data;
                        break;
                    case DfuService.LOG_LEVEL_FIRMWARE:
                        data = intent.getStringExtra(DfuService.EXTRA_DATA);
                        m_MicroBitFirmware = data;
                        break;
                }
            }
        }

    }

}