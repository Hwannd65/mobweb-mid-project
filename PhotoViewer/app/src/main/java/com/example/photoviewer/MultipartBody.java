package com.example.photoviewer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Text fields and an image stream serialized as a single multipart HTTP body. */
final class MultipartBody implements Closeable {
    final String boundary;
    final File file;

    private MultipartBody(String boundary, File file) {
        this.boundary = boundary;
        this.file = file;
    }

    static MultipartBody create(Context context, Uri imageUri, Map<String, String> fields)
            throws IOException {
        String boundary = "PhotoViewer-" + UUID.randomUUID();
        File file = File.createTempFile("photo-upload-", ".multipart", context.getCacheDir());
        boolean complete = false;
        try {
            ContentResolver resolver = context.getContentResolver();
            String filename = displayName(resolver, imageUri)
                    .replace('\r', '_').replace('\n', '_').replace('"', '_')
                    .replace('\\', '_').replace('/', '_');
            String mimeType = resolver.getType(imageUri);
            if (mimeType == null || !mimeType.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) {
                mimeType = "application/octet-stream";
            }
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    writeUtf8(output, "--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"" + field.getKey()
                            + "\"\r\n\r\n" + field.getValue() + "\r\n");
                }
                writeUtf8(output, "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"image\"; filename=\""
                        + filename + "\"\r\n"
                        + "Content-Type: " + mimeType + "\r\n\r\n");
                try (InputStream image = resolver.openInputStream(imageUri)) {
                    if (image == null) throw new IOException("선택한 이미지를 열 수 없습니다.");
                    copy(image, output);
                }
                writeUtf8(output, "\r\n--" + boundary + "--\r\n");
            }
            complete = true;
            return new MultipartBody(boundary, file);
        } finally {
            if (!complete && !file.delete()) file.deleteOnExit();
        }
    }

    static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        }
        String name = uri.getLastPathSegment();
        return name == null || name.isEmpty() ? "upload.jpg" : name;
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("전송이 중단되었습니다.");
            }
            output.write(buffer, 0, count);
        }
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (!file.delete()) file.deleteOnExit();
    }
}
