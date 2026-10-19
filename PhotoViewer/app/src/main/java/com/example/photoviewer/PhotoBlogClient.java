package com.example.photoviewer;

import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

// ch05 pp. 21-22: API GET -> JSON array -> image URL GET -> Bitmap list.
// Called from a worker thread, never directly from the screen's main thread.
public final class PhotoBlogClient {
    private static final String TAG = "PhotoBlogClient";
    private final String siteUrl;

    public PhotoBlogClient(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public List<Bitmap> downloadImages() throws IOException, JSONException {
        String json = getJson(new URL(siteUrl + "/api_root/Post/"));
        JSONArray posts = new JSONArray(json);
        List<Bitmap> images = new ArrayList<>();
        for (int index = 0; index < posts.length(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("다운로드가 중단되었습니다.");
            }
            JSONObject post = posts.getJSONObject(index);
            String imageUrl = post.optString("image", "");
            if (!imageUrl.isEmpty() && !"null".equals(imageUrl)) {
                images.add(getImage(new URL(imageUrl)));
            }
        }
        return images;
    }

    public JSONObject uploadPost(Context context, Uri imageUri, int authorId,
                                 String title, String text) throws IOException, JSONException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("author", Integer.toString(authorId));
        fields.put("title", title);
        fields.put("text", text);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        fields.put("published_date", dateFormat.format(new Date()));

        // A cache file gives an exact Content-Length without holding the entire image in RAM.
        try (MultipartBody body = MultipartBody.create(context, imageUri, fields)) {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(siteUrl + "/api_root/Post/").openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + body.boundary);
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setFixedLengthStreamingMode(body.file.length());
                try (InputStream input = new FileInputStream(body.file);
                     OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                    MultipartBody.copy(input, output);
                }
                int status = connection.getResponseCode();
                Log.d(TAG, "POST /api_root/Post/ -> HTTP " + status);
                String responseBody = readBody(status >= 400
                        ? connection.getErrorStream() : connection.getInputStream());
                if (status != HttpURLConnection.HTTP_CREATED) {
                    throw new ApiException(status, responseBody);
                }
                return new JSONObject(responseBody);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        }
    }

    public static final class ApiException extends IOException {
        private final int status;
        private final String responseBody;

        ApiException(int status, String responseBody) {
            super("서버 응답 HTTP " + status);
            this.status = status;
            this.responseBody = responseBody;
        }

        public int getStatus() { return status; }
        public String getResponseBody() { return responseBody; }
    }

    private HttpURLConnection openGet(URL url, String accept) throws IOException {
        URLConnection rawConnection = url.openConnection();
        if (!(rawConnection instanceof HttpURLConnection)) {
            throw new IOException("HTTP 또는 HTTPS 이미지 주소가 필요합니다.");
        }
        HttpURLConnection connection = (HttpURLConnection) rawConnection;
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", accept);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);
        // No Authorization header: the current Django API permits anonymous access.
        return connection;
    }

    private void requireOk(HttpURLConnection connection) throws IOException {
        // getResponseCode() starts the request and waits for the response headers.
        int status = connection.getResponseCode();
        Log.d(TAG, "GET " + connection.getURL().getPath() + " -> HTTP " + status);
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("서버 응답 HTTP " + status);
        }
    }

    private String getJson(URL url) throws IOException {
        HttpURLConnection connection = openGet(url, "application/json");
        try {
            requireOk(connection);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                return body.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private Bitmap getImage(URL url) throws IOException {
        HttpURLConnection connection = openGet(url, "image/*");
        try {
            requireOk(connection);
            try (InputStream input = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) {
                    throw new IOException("다운로드한 데이터를 이미지로 읽을 수 없습니다.");
                }
                return bitmap;
            }
        } finally {
            connection.disconnect();
        }
    }
}
