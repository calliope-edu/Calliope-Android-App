package cc.calliope.mini.ui.fragment.web;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AndroidBleBridge {
  private final Context ctx;
  private final WebView webView;
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic rxChar; // write here
  private BluetoothGattCharacteristic txChar; // notify here

  // Hardcode під твій девайс
  private static final String DEVICE_MAC = "D8:0F:45:61:FE:65";

  // NUS (Nordic UART Service)
  private static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
  // Пишемо СЮДИ (RX characteristic на девайсі = write WITHOUT response)
  private static final UUID NUS_RX_UUID     = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
  // Читаємо ЗВІДТИ (TX characteristic на девайсі = notify)
  private static final UUID NUS_TX_UUID     = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

  // CCCD для enable notifications
  private static final UUID CLIENT_CHAR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  public AndroidBleBridge(Context ctx, WebView webView) {
    this.ctx = ctx.getApplicationContext();
    this.webView = webView;
  }

  @JavascriptInterface
  public void connect() {
    runOnUi(() -> {
      try {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
          logToJs("Bluetooth disabled");
          return;
        }
        BluetoothDevice dev = adapter.getRemoteDevice(DEVICE_MAC);
        if (dev == null) {
          logToJs("Device not found by MAC");
          return;
        }
        logToJs("Connecting to " + DEVICE_MAC + " ...");
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
      } catch (Exception e) {
        logToJs("connect() error: " + e.getMessage());
      }
    });
  }

  @JavascriptInterface
  public void writeText(String text) {
    runOnUi(() -> {
      if (gatt == null || rxChar == null) {
        logToJs("Not connected");
        return;
      }
      try {
        // Більшість прошивок очікує \n в кінці — збережемо поведінку сторінки
        String payload = text.endsWith("\n") ? text : text + "\n";
        rxChar.setValue(payload.getBytes(StandardCharsets.UTF_8));
        boolean ok = gatt.writeCharacteristic(rxChar);
        logToJs("writeText('" + text + "') -> " + ok);
      } catch (Exception e) {
        logToJs("writeText error: " + e.getMessage());
      }
    });
  }

  private final BluetoothGattCallback callback = new BluetoothGattCallback() {
    @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        logToJs("GATT connected, discovering services...");
        g.discoverServices();
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        logToJs("GATT disconnected (status=" + status + ")");
        rxChar = txChar = null;
      }
    }

    @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        logToJs("Service discovery failed: " + status);
        return;
      }
      BluetoothGattService nus = g.getService(NUS_SERVICE_UUID);
      if (nus == null) { logToJs("NUS service not found"); return; }

      rxChar = nus.getCharacteristic(NUS_RX_UUID);
      txChar = nus.getCharacteristic(NUS_TX_UUID);
      if (rxChar == null || txChar == null) {
        logToJs("NUS RX/TX characteristics missing");
        return;
      }

      // enable notifications on TX
      boolean set = g.setCharacteristicNotification(txChar, true);
      BluetoothGattDescriptor cccd = txChar.getDescriptor(CLIENT_CHAR_CONFIG_UUID);
      if (cccd != null) {
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        g.writeDescriptor(cccd);
      }
      logToJs("NUS ready (notify=" + set + ")");
      // опційно: повідомити сторінку що “під’єднано”
      evalJs("if(window.onBleReady){onBleReady();}");
    }

    @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
      if (NUS_TX_UUID.equals(ch.getUuid())) {
        String s = new String(ch.getValue(), StandardCharsets.UTF_8);
        // Проброс у сторінку
        evalJs("if(window.onUart){onUart(" + jsString(s) + ");}");
        logToJs("RX: " + s);
      }
    }

    @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
      logToJs("onWrite(" + ch.getUuid() + ") status=" + status);
    }
  };

  // helpers
  private void runOnUi(Runnable r) {
    if (webView == null) return;
    webView.post(r);
  }
  private void evalJs(String js) {
    if (webView == null) return;
    webView.post(() -> webView.evaluateJavascript(js, null));
  }
  private void logToJs(String msg) {
    Log.d("BLE", msg);
    evalJs("console.log(" + jsString("[AndroidBle] " + msg) + ");");
  }
  private String jsString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n","\\n") + "\"";
  }
}