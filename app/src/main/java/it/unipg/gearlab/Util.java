package it.unipg.gearlab;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class Util {
    public static File copyKMZToExternalStorage(Context context, String fileName) {
        File externalFile = new File(context.getExternalFilesDir(null), fileName);
        try (InputStream inputStream = context.getAssets().open(fileName);
             FileOutputStream outputStream = new FileOutputStream(externalFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return externalFile;
    }

    public static File copyFileToTempFolder(Context context, Uri uri) {
        File tempFile = null;

        // Create a temporary file in the cache directory
        tempFile = new File(context.getCacheDir(), "tempFile.kmz");

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            if (inputStream == null) {
                return null;
            }

            // Buffer to read and write the file in chunks
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            // Flush the output stream to ensure all data is written
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tempFile;
    }
}
