package cc.calliope.mini.ui.activity;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Trampoline behind the flashing notifications (ours and Nordic DFU's,
 * which requires an Activity class as its target). Tapping a notification
 * must bring the running app to the front as it is, and only open the
 * flashing screen when the app's task is gone.
 */
public class NotificationActivity extends AppCompatActivity {

    /** The content intent every flashing notification uses. */
    public static PendingIntent contentIntent(Context context) {
        return PendingIntent.getActivity(
                context, 0, new Intent(context, NotificationActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If this activity is the root activity of the task, the app is not running
        if (isTaskRoot()) {
            // Start the app before finishing
            final Intent intent = new Intent(this, FlashingActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getIntent().getExtras() != null) {
                intent.putExtras(getIntent().getExtras()); // copy all extras
            }
            startActivity(intent);
        }
        // Now finish, which will drop you to the activity at which you were at the top of the task stack
        finish();
    }
}
