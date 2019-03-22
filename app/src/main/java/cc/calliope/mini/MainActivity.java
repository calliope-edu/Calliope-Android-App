package cc.calliope.mini;

import android.content.Intent;
import android.os.Bundle;
import android.support.constraint.ConstraintLayout;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import cc.calliope.mini.adapter.ExtendedBluetoothDevice;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Intent intent = getIntent();
        final ExtendedBluetoothDevice device = intent.getParcelableExtra("cc.calliope.mini.EXTRA_DEVICE");
        if (device != null) {

            final String deviceName = device.getName();

            final TextView deviceInfo = findViewById(R.id.deviceInfo);
            deviceInfo.setText(R.string.text_connected_with+" "+deviceName);

            final ImageView pattern1 = findViewById(R.id.pattern1);
            final ImageView pattern2 = findViewById(R.id.pattern2);
            final ImageView pattern3 = findViewById(R.id.pattern3);
            final ImageView pattern4 = findViewById(R.id.pattern4);
            final ImageView pattern5 = findViewById(R.id.pattern5);
            pattern1.setImageResource(device.getDevicePattern(0));
            pattern2.setImageResource(device.getDevicePattern(1));
            pattern3.setImageResource(device.getDevicePattern(2));
            pattern4.setImageResource(device.getDevicePattern(3));
            pattern5.setImageResource(device.getDevicePattern(4));
        }

        ImageView button_info = findViewById(R.id.button_info);
        button_info.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final Intent intent = new Intent(MainActivity.this, InfoActivity.class);
                startActivity(intent);
            }
        });

        ConstraintLayout button_scanner = findViewById(R.id.button_scanner);
        button_scanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final Intent intent = new Intent(MainActivity.this, ScannerActivity.class);
                startActivity(intent);
            }
        });

        ConstraintLayout buttonDemoScript = findViewById(R.id.buttonDemoScript);
        buttonDemoScript.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final Intent intent = new Intent(MainActivity.this, editorAcitvity.class);
                if(device != null) intent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);
                intent.putExtra("TARGET_NAME", "BIBLIOTHEK");
                intent.putExtra("TARGET_URL", "https://calliope.cc/subdomain_minieditor/calliope.html");
                startActivity(intent);
            }
        });

        ConstraintLayout button_editor = findViewById(R.id.button_editor);
        button_editor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final Intent intent = new Intent(MainActivity.this, selectEditorActivity.class);
                if(device != null) intent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);
                startActivity(intent);
            }
        });

        ConstraintLayout button_code = findViewById(R.id.button_code);
        button_code.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final Intent intent = new Intent(MainActivity.this, myCodeActivity.class);
                if(device != null) intent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);
                startActivity(intent);
            }
        });


    }

    public void foo() {
 //       final Intent intent = new Intent(this, ScannerActivity.class);
//        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
  //      startActivity(intent);
    }








}
