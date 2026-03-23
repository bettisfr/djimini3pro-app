package it.unipg.gearlab;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MissionFileRepository {
    private MissionFileRepository() {
    }

    @Nullable
    public static File copyKMZToExternalStorage(@NonNull Context context, @NonNull String fileName) {
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir == null) {
            return null;
        }
        File externalFile = new File(externalDir, fileName);
        try (InputStream inputStream = context.getAssets().open(fileName);
             FileOutputStream outputStream = new FileOutputStream(externalFile)) {
            copy(inputStream, outputStream);
            return externalFile;
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    public static File copyFileToTempFolder(@NonNull Context context, @NonNull Uri uri) {
        File tempFile = new File(context.getCacheDir(), "tempFile.kmz");
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            if (inputStream == null) {
                return null;
            }
            copy(inputStream, outputStream);
            outputStream.flush();
            return tempFile;
        } catch (IOException e) {
            return null;
        }
    }

    private static void copy(@NonNull InputStream inputStream, @NonNull FileOutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }
    }
}
