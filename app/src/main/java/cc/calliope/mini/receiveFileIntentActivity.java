package cc.calliope.mini;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class receiveFileIntentActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive_file_intent);



        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_VIEW.equals(action) && type != null) {
            Uri uri = getIntent().getData();
            String extension = uri.toString().substring(uri.toString().lastIndexOf("."));
            Log.i("INTENT", "TYPE: "+type+" File: "+uri+" Ext: "+extension);

            if(extension.equals(".hex")) {
                String filename = "RECEIVED-" + System.currentTimeMillis() + ".hex";
                File file = new File(getFilesDir() + File.separator + filename);
                if (file.exists())
                    file.delete();
                try {
                    file.createNewFile();
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.w("CreateFile", "Error writing " + file);
                }

                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(file);
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.close();
                    in.close();

                    Toast.makeText(getApplicationContext(), "Datei gespeichert", Toast.LENGTH_LONG).show();

                } catch (Exception e) {
                    e.printStackTrace();
                    Log.w("CreateFile", "Error" + file);
                }
            } else {
                Toast.makeText(getApplicationContext(), "Falscher Dateityp", Toast.LENGTH_LONG).show();
            }

        } else {
            // Handle other intents, such as being started from the home screen
            Log.i("INTENT", "NOPE");
            Toast.makeText(getApplicationContext(), "Keine Datei empfangen", Toast.LENGTH_LONG).show();
        }

        final Intent targetIntent = new Intent(receiveFileIntentActivity.this, MainActivity.class);
        startActivity(targetIntent);



    }
}
