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
import java.util.LinkedHashMap;
import java.util.Map;

// ch05 pp. 21-22: API GET -> JSON array -> image URL GET -> Bitmap list.
// Called from a worker thread, never directly from the screen's main thread.
public final class PhotoBlogClient {
    private static final String TAG = "PhotoBlogClient";
    private final String siteUrl;
    // Credentials live only in this client instance, never in preferences, files or logs.
    private volatile Session session;
    interface SessionListener { void changed(String username); }
    private SessionListener sessionListener;
    void setSessionListener(SessionListener listener) { sessionListener = listener; }
    public void clearSession() {
        session = null;
        if (sessionListener != null) sessionListener.changed("");
    }

    public JSONObject register(String username, String password, String confirmation)
            throws IOException, JSONException {
        JSONObject data = new JSONObject();
        data.put("username", username);
        data.put("password", password);
        data.put("password_confirm", confirmation);
        return new JSONObject(accountRequest("POST", "/api/register/", data, null,
                HttpURLConnection.HTTP_CREATED));
    }

    private static final class Session {
        final String token;
        final int userId;
        final String username;

        Session(String token, int userId, String username) {
            this.token = token;
            this.userId = userId;
            this.username = username;
        }
    }

    public boolean isLoggedIn() { return session != null; }

    public String getCurrentUsername() {
        Session current = session;
        return current == null ? "" : current.username;
    }

    public synchronized void login(String username, String password) throws IOException, JSONException {
        JSONObject credentials = new JSONObject();
        credentials.put("username", username);
        credentials.put("password", password);
        JSONObject result = new JSONObject(accountRequest("POST", "/api-token-auth/",
                credentials, null, HttpURLConnection.HTTP_OK));
        String token = result.getString("token");
        if (token.isEmpty() || token.contains("\r") || token.contains("\n")) {
            throw new JSONException("Invalid token response");
        }
        // Do not display a signed-in identity until the server verifies this token.
        JSONObject user = new JSONObject(accountRequest("GET", "/api/me/", null,
                token, HttpURLConnection.HTTP_OK));
        int userId = user.getInt("id");
        String name = user.getString("username");
        if (userId <= 0 || name.isEmpty()) throw new JSONException("Invalid user response");
        session = new Session(token, userId, name);
        if (sessionListener != null) sessionListener.changed(name);
    }

    public synchronized void logout() throws IOException, JSONException {
        Session current = session;
        if (current == null) return;
        try {
            accountRequest("POST", "/api/logout/", new JSONObject(), current.token,
                    HttpURLConnection.HTTP_NO_CONTENT);
        } catch (ApiException error) {
            // An already revoked token is also a completed logout on this device.
            if (error.getStatus() != HttpURLConnection.HTTP_UNAUTHORIZED) throw error;
        }
        clearSession();
    }

    private String accountRequest(String method, String path, JSONObject body,
                                  String token, int expectedStatus) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(siteUrl + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            if (token != null) connection.setRequestProperty("Authorization", "Token " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            // Neither credentials nor response bodies (which may contain a token) are logged.
            Log.d(TAG, method + " " + path + " -> HTTP " + status);
            String response = readBody(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            if (status != expectedStatus) throw new ApiException(status, response);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    public PhotoBlogClient(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public List<Bitmap> downloadImages() throws IOException, JSONException {
        List<Bitmap> images = new ArrayList<>();
        for (PhotoPost post : downloadPosts("", "title")) {
            if (post.image != null) images.add(post.image);
        }
        return images;
    }

    public List<PhotoPost> downloadPosts(String keyword, String field) throws IOException, JSONException {
        return loadPosts(keyword, field, null);
    }

    public List<PhotoPost> downloadPosts(String keyword, String field, String sort) throws IOException, JSONException {
        if (!java.util.Arrays.asList("newest", "oldest", "recommendations", "comments").contains(sort)) {
            throw new IOException("지원하지 않는 정렬 방식입니다.");
        }
        return loadPosts(keyword, field, sort);
    }

    private List<PhotoPost> loadPosts(String keyword, String field, String sort) throws IOException, JSONException {
        Uri.Builder url = Uri.parse(siteUrl + "/api_root/Post/").buildUpon();
        if (!keyword.trim().isEmpty()) {
            url.appendQueryParameter("search", keyword.trim());
            url.appendQueryParameter("search_field", field);
        }
        if (sort != null) url.appendQueryParameter("sort", sort);
        JSONArray posts = new JSONArray(getJson(new URL(url.build().toString()), !keyword.trim().isEmpty(), sort));
        List<PhotoPost> results = new ArrayList<>();
        for (int index = 0; index < posts.length(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("다운로드가 중단되었습니다.");
            JSONObject post = posts.getJSONObject(index);
            String imageUrl = post.optString("image", "");
            Bitmap image = imageUrl.isEmpty() || "null".equals(imageUrl)
                    ? null : getImage(new URL(imageUrl));
            int authorId = post.optInt("author", 0);
            results.add(new PhotoPost(post.optInt("id", 0), authorId,
                    post.optString("author_username", "작성자 #" + authorId),
                    post.getString("title"), post.optString("text", ""), image,
                    post.optInt("recommendation_count", 0), post.optBoolean("is_recommended", false),
                    post.optInt("id", 0) > 0 && post.has("recommendation_count") && post.has("is_recommended"),
                    post.optInt("comment_count", 0), post.optInt("id", 0) > 0 && post.has("comment_count")));
        }
        return results;
    }

    public List<PhotoComment> getComments(int postId) throws IOException, JSONException {
        JSONArray data = new JSONArray(getJson(new URL(siteUrl + "/api_root/Post/" + postId + "/comments/"), false));
        List<PhotoComment> comments = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) comments.add(new PhotoComment(data.getJSONObject(i)));
        return comments;
    }

    public JSONObject createComment(int postId, String text) throws IOException, JSONException {
        Session current = session;
        if (current == null) throw new ApiException(401, "{}");
        JSONObject body = new JSONObject();
        body.put("text", text);
        try {
            return new JSONObject(accountRequest("POST", "/api_root/Post/" + postId + "/comments/",
                    body, current.token, HttpURLConnection.HTTP_CREATED));
        } catch (ApiException error) {
            if (error.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) clearSession();
            throw error;
        }
    }

    public void deleteComment(int postId, int commentId) throws IOException {
        Session current = session;
        if (current == null) throw new ApiException(401, "{}");
        try {
            accountRequest("DELETE", "/api_root/Post/" + postId + "/comments/" + commentId + "/",
                    null, current.token, HttpURLConnection.HTTP_NO_CONTENT);
        } catch (ApiException error) {
            if (error.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) clearSession();
            throw error;
        }
    }

    public JSONObject setRecommendation(int postId, boolean recommended) throws IOException, JSONException {
        Session current = session;
        if (current == null) throw new ApiException(401, "{}");
        if (postId <= 0) throw new IOException("서버의 추천 기능 업데이트가 필요합니다.");
        try {
            return new JSONObject(accountRequest(recommended ? "PUT" : "DELETE",
                    "/api_root/Post/" + postId + "/recommendation/",
                    recommended ? new JSONObject() : null, current.token, HttpURLConnection.HTTP_OK));
        } catch (ApiException error) {
            if (error.getStatus() == HttpURLConnection.HTTP_UNAUTHORIZED) clearSession();
            throw error;
        }
    }

    public JSONObject uploadPost(Context context, Uri imageUri,
                                 String title, String text) throws IOException, JSONException {
        Session current = session;
        if (current == null) throw new ApiException(401, "{}");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("text", text);
        // The server supplies the author and timestamps from the authenticated request.

        // A cache file gives an exact Content-Length without holding the entire image in RAM.
        try (MultipartBody body = MultipartBody.create(context, imageUri, fields)) {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(siteUrl + "/api_root/Post/").openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Token " + current.token);
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
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) clearSession();
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
        // Public reads and image downloads do not need credentials. Never send tokens to image URLs.
        return connection;
    }

    private void requireOk(HttpURLConnection connection) throws IOException {
        // getResponseCode() starts the request and waits for the response headers.
        int status = connection.getResponseCode();
        URL url = connection.getURL();
        // Search phrases are user data: log the destination, without its query string.
        Log.d(TAG, "GET " + url.getProtocol() + "://" + url.getAuthority()
                + url.getPath() + " -> HTTP " + status);
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("서버 응답 HTTP " + status);
        }
    }

    private String getJson(URL url, boolean requiresSearch) throws IOException {
        return getJson(url, requiresSearch, null);
    }

    private String getJson(URL url, boolean requiresSearch, String requestedSort) throws IOException {
        HttpURLConnection connection = openGet(url, "application/json");
        try {
            // Only the API JSON request receives credentials; image URL GETs stay anonymous.
            Session current = session;
            if (current != null) connection.setRequestProperty("Authorization", "Token " + current.token);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                clearSession();
                throw new ApiException(401, "{}");
            }
            requireOk(connection);
            if (requiresSearch && !"1".equals(connection.getHeaderField("X-PhotoBlog-Search"))) {
                throw new IOException("서버의 검색 기능 업데이트가 필요합니다.");
            }
            if (requestedSort != null && !requestedSort.equals(connection.getHeaderField("X-PhotoBlog-Sort"))) {
                throw new IOException("서버의 정렬 기능 업데이트가 필요합니다.");
            }
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
