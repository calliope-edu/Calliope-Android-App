package cc.calliope.mini.adapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.github.marlonlom.utilities.timeago.TimeAgo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

import cc.calliope.mini.DFUActivity;
import cc.calliope.mini.R;
import cc.calliope.mini.datamodel.HexFile;

public class HexFilesAdapter extends ArrayAdapter<HexFile> {

    Button btnUpload,btnShare;
    ExtendedBluetoothDevice device;
    Context ParentContext;

    public HexFilesAdapter(Context context, ArrayList<HexFile> users, ExtendedBluetoothDevice SelectedDevice) {

        super(context, 0, users);
        device = SelectedDevice;
        ParentContext = context;
    }



    @Override

    public View getView(int position, View convertView, ViewGroup parent) {

        // Get the data item for this position

        HexFile HexFile = getItem(position);

        // Check if an existing view is being reused, otherwise inflate the view

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.hex_file_list_item, parent, false);
        }

        // Lookup view for data population
        TextView tvSource = (TextView) convertView.findViewById(R.id.tvSource);
        TextView tvDate = (TextView) convertView.findViewById(R.id.tvDate);
        TextView tvTime = (TextView) convertView.findViewById(R.id.tvTime);

//        Locale LocaleBylanguageTag = Locale.forLanguageTag("de");
//        TimeAgoMessages messages = new TimeAgoMessages.Builder().withLocale(LocaleBylanguageTag).build();
//
//        String timeAgoText = TimeAgo.using(HexFile.lastModified, messages);
        String timeAgoText = TimeAgo.using(HexFile.lastModified);

        // Populate the data into the template view using the data object
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEEE dd.MM.yyyy", Locale.GERMAN);
        String formattedDate = new SimpleDateFormat("EEEE dd.MM.yyyy", Locale.GERMAN).format(HexFile.lastModified);
        String formattedTime = new SimpleDateFormat("hh:mm:ss", Locale.GERMAN).format(HexFile.lastModified)+" Uhr";

        tvDate.setText(formattedDate);
        tvTime.setText(formattedTime+" - "+timeAgoText);
        tvSource.setText(HexFile.File.getName() + "");


        btnUpload = (Button) convertView.findViewById(R.id.btnUpload);
        btnUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.i("SELECTED Position", position + "");
                Log.i("SLECTED HexFile", getHexFileName(position));
                String selectedItem = getHexFile(position).toString();

                if (device != null) {
                    final Intent intent = new Intent(ParentContext, DFUActivity.class); // ParentContext.this ?
                    intent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);
                    intent.putExtra("EXTRA_FILE", selectedItem);
                    ParentContext.startActivity(intent);
                } else {
                    Toast.makeText(ParentContext, R.string.upload_no_mini_connected, Toast.LENGTH_LONG).show();
                }
            }
        });

        btnShare = (Button) convertView.findViewById(R.id.btnShare);
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.i("BUTTON Share", "AT "+position+" File: "+ getHexFile(position));



                File file = getHexFile(position);

                Intent intentShareFile = new Intent(Intent.ACTION_SEND);
               // intentShareFile.setType(URLConnection.guessContentTypeFromName(file.getName()));
                intentShareFile.setType("text/plain");
                intentShareFile.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Uri uri = FileProvider.getUriForFile(ParentContext, "cc.calliope.fileprovider",  getHexFile(position));
                intentShareFile.putExtra(Intent.EXTRA_STREAM, uri);

                //if you need
                //intentShareFile.putExtra(Intent.EXTRA_SUBJECT,"Sharing File Subject);
                //intentShareFile.putExtra(Intent.EXTRA_TEXT, "Sharing File Description");

                ParentContext.startActivity(Intent.createChooser(intentShareFile, "Share File"));

//                val shareIntent = Intent()
//                shareIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
//                val uri = FileProvider.getUriForFile(this, "cc.calliope.fileprovider", tmpURL)
//                shareIntent.action = Intent.ACTION_SEND
//                shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
//                shareIntent.type = "text/plain"
//                startActivity(Intent.createChooser(shareIntent, "Datei teilen mit"))
            }
        });

        // Return the completed view to render on screen

        return convertView;
    }

    public File getHexFile(int position) {
        HexFile HexFile = getItem(position);
        return HexFile.File;
    }

    public String getHexFileName(int position) {
        HexFile HexFile = getItem(position);
        return HexFile.File.getName();
    }

}
