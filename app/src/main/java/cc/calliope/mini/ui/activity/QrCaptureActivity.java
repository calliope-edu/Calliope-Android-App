package cc.calliope.mini.ui.activity;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * QR capture screen that follows the device's current orientation instead of
 * the ZXing default (which forces landscape). All behaviour is inherited from
 * {@link CaptureActivity}; the orientation is set via android:screenOrientation
 * on this activity's manifest entry.
 */
public class QrCaptureActivity extends CaptureActivity {
}
