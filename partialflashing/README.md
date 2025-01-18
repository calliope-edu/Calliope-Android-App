# micro:bit Partial Flashing Library for Android

This library provides partial flashing capabilities to an Android application.

It will process a hex file created in MakeCode or the Python Editor and flash only the user script.
To modularize the code, information is passed to and from the library using Intents.

## Including the lib in an application

Create a class that extends the base service:

```
package com.microbitreactnative.pf;

import android.app.Activity;

import org.microbit.android.partialflashing.PartialFlashingBaseService;

import com.microbitreactnative.NotificationActivity;

public class PartialFlashingService extends PartialFlashingBaseService {

    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }
}
``` 

## To start a partial flash

Send an Intent with the micro:bit's device address (scanning and bonding is not handled by this library) and the file path of the hex file to flash.

```
    MainApplication application = MainApplication.getApp();

    Log.v("MicrobitDFU", "Start Partial Flash");

    // final Intent service = new Intent(application, PartialFlashingService.class);
    final Intent service = new Intent(application, PartialFlashingService.class);
    service.putExtra("deviceAddress", deviceAddress);
    service.putExtra("filepath", filePath); // a path or URI must be provided.

    application.startService(service);
```

## Receiving progress updates

Currently the library only sends progress as percentage updates (0-100%). These are broadcast during the flashing process and can be obtained using a LocalBroadcastManager.

An example that forwards the information to a React Native app:

```
...
  public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
  public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
  public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
  public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
  public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";

  private ReactContext mReactContext;
  private LocalBroadcastReceiver  mLocalBroadcastReceiver;

  public MicrobitDFUModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.mReactContext = reactContext;
    this.mLocalBroadcastReceiver = new LocalBroadcastReceiver();
    LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(reactContext);
    localBroadcastManager.registerReceiver(mLocalBroadcastReceiver, new IntentFilter(BROADCAST_PROGRESS_PF));
  }

  public class LocalBroadcastReceiver extends BroadcastReceiver {
         @Override
         public void onReceive(Context context, Intent intent) {
               // Doesn't need to be precise so using int
               int percentage = intent.getIntExtra(EXTRA_PROGRESS_PF, 0);

               mReactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                      .emit("flashingProgress", percentage);
         }
  }

...
```

## Partial Flashing Algorithm

More information on the partial flashing algorithm can be found [here](https://github.com/lancaster-university/codal-microbit-v2/blob/master/docs/MemoryMap.md).

## Code of Conduct

Trust, partnership, simplicity and passion are our core values we live and breathe in our daily work life and within our projects. Our open-source projects are no exception. We have an active community which spans the globe and we welcome and encourage participation and contributions to our projects by everyone. We work to foster a positive, open, inclusive and supportive environment and trust that our community respects the micro:bit code of conduct. Please see our [code of conduct](https://microbit.org/safeguarding/) which outlines our expectations for all those that participate in our community and details on how to report any concerns and what would happen should breaches occur.
