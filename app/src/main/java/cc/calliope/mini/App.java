package cc.calliope.mini;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class App extends Application {
    private static final String FILE_NAME = "one_time_pairing.hex";
    private static final String LIBRARY_DIR = "LIBRARY";

    @Override
    public void onCreate() {
        super.onCreate();
        AppContext.initialize(this);
        copyFileToInternalStorage();
    }

    private void copyFileToInternalStorage() {
        File libraryDir = new File(getFilesDir(), LIBRARY_DIR);
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }

        File file = new File(libraryDir, FILE_NAME);
        if (!file.exists()) {
            try (InputStream inputStream = getResources().openRawResource(R.raw.one_time_pairing);
                 FileOutputStream outputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            } catch (IOException e) {
                Log.e("App", "Error copying file", e);
            }
        }
    }
}