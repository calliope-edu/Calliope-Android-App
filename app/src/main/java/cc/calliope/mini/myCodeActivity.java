package cc.calliope.mini;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

import cc.calliope.mini.adapter.ExtendedBluetoothDevice;
import cc.calliope.mini.adapter.HexFilesAdapter;
import cc.calliope.mini.datamodel.HexFile;

public class myCodeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_code);
//        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);

        final Intent intent = getIntent();
        final ExtendedBluetoothDevice device = intent.getParcelableExtra("cc.calliope.mini.EXTRA_DEVICE");
        if (device != null) {
            final String deviceName = device.getName();
            final String deviceAddress = device.getAddress();
            Log.i("DEVICE", deviceName);
            final TextView deviceInfo = findViewById(R.id.deviceInfo);
        }

    //        File mydir = getFilesDir();
    //        Log.i("Datei", getFilesDir()+"");
    //        File lister = mydir.getAbsoluteFile();
    //        for (String list : lister.list())
    //        {
    //            Log.i("Datei", list);
    //        }
    //
    //
    //        final ListView listView = findViewById(R.id.code_list_view);
    //        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
    //                this,
    //                android.R.layout.simple_list_item_1,
    //                lister.list());
    //
    //        listView.setAdapter(adapter);


        // Construct the data source
        ArrayList<HexFile> arrayOfUsers = new ArrayList<HexFile>();
        // Create the adapter to convert the array to views
        HexFilesAdapter adapter = new HexFilesAdapter(this, arrayOfUsers, device);
        // Attach the adapter to a ListView
        ListView listView = (ListView) findViewById(R.id.code_list_view);
        listView.setAdapter(adapter);

    //        File mydir = getFilesDir();
        File dir = new File(getFilesDir().toString());
    //        Log.i("Datei", getFilesDir()+"");
    //        File lister = mydir.getAbsoluteFile();
        File[] files = dir.listFiles();
    //        for (String list : lister.list())
        for (File file : files) {
                Log.i("Datei", file.toString());
    //            adapter.add(new HexFile(list, "San Diego"));
                Long lastmodified = file.lastModified();
                adapter.insert(new HexFile(file, lastmodified), 0);
        }


        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.i("SELECTED Position", position + "");
                Log.i("SLECTED HexFile", adapter.getHexFileName(position));
                String selectedItem = adapter.getHexFile(position).toString();

                if (device != null) {
                    final Intent intent = new Intent(myCodeActivity.this, DFUActivity.class);
                    intent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);
                    intent.putExtra("EXTRA_FILE", selectedItem);
                    startActivity(intent);
                } else {
                    Toast.makeText(getApplicationContext(), R.string.upload_no_mini_connected, Toast.LENGTH_LONG).show();
                }
            }
        });

    }

}
