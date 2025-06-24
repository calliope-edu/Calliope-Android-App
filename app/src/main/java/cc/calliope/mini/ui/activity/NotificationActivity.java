package cc.calliope.mini.ui.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;

public class NotificationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        GoogleAnalyticsManager.getInstance().sendViewEventStats(NotificationActivity.class.getSimpleName());

        // If this activity is the root activity of the task, the app is not running
        if (isTaskRoot()) {
            // Start the app before finishing
            final Intent intent = new Intent(this, FlashingActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getIntent().getExtras() != null){
                intent.putExtras(getIntent().getExtras()); // copy all extras
            }
            startActivity(intent);
        }
        // Now finish, which will drop you to the activity at which you were at the top of the task stack
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
//        GoogleAnalyticsManager.getInstance().activityStart(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
//        GoogleAnalyticsManager.getInstance().activityStop(this);
    }
}