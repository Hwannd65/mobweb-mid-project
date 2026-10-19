package com.example.photoviewer;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local HTTP contract tests only; never sends credentials to the deployed server. */
@RunWith(AndroidJUnit4.class)
public class AccountProtocolTest {
    private static final String TOKEN = "synthetic-test-token";

    @Test public void loginVerifiesIdentityAndLogoutRevokesToken() throws Exception {
        try (Stub server = new Stub(okToken(), okUser(), new Reply(204, ""))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            client.login("test-user", " test-password ");
            assertTrue(client.isLoggedIn());
            assertEquals("server-user", client.getCurrentUsername());
            client.logout();
            assertFalse(client.isLoggedIn());
            List<Request> requests = server.requests();
            assertEquals("POST /api-token-auth/ HTTP/1.1", requests.get(0).line);
            assertNull(requests.get(0).headers.get("authorization"));
            JSONObject credentials = new JSONObject(requests.get(0).body);
            assertEquals("test-user", credentials.getString("username"));
            assertEquals(" test-password ", credentials.getString("password"));
            assertEquals("GET /api/me/ HTTP/1.1", requests.get(1).line);
            assertEquals("Token " + TOKEN, requests.get(1).headers.get("authorization"));
            assertEquals("POST /api/logout/ HTTP/1.1", requests.get(2).line);
            assertEquals("Token " + TOKEN, requests.get(2).headers.get("authorization"));
        }
    }

    @Test public void wrongCredentialsDoNotCreateSession() throws Exception {
        try (Stub server = new Stub(new Reply(400, "{\"non_field_errors\":[\"invalid\"]}"))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            try {
                client.login("test-user", "wrong");
                fail("A rejected password must not create a session");
            } catch (PhotoBlogClient.ApiException error) {
                assertEquals(400, error.getStatus());
            }
            assertFalse(client.isLoggedIn());
            assertEquals(1, server.requests().size());
        }
    }

    @Test public void tokenWithoutVerifiedUserDoesNotCreateSession() throws Exception {
        try (Stub server = new Stub(okToken(), new Reply(401, "{\"detail\":\"Invalid token\"}"))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            try {
                client.login("test-user", "test-password");
                fail("The token must be verified by the identity endpoint");
            } catch (PhotoBlogClient.ApiException error) {
                assertEquals(401, error.getStatus());
            }
            assertFalse(client.isLoggedIn());
            assertEquals(2, server.requests().size());
        }
    }

    @Test public void logoutFailureKeepsSessionUntilRetrySucceeds() throws Exception {
        try (Stub server = new Stub(okToken(), okUser(), new Reply(500, "{}"), new Reply(204, ""))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            client.login("test-user", "test-password");
            try {
                client.logout();
                fail("A server error must be reported");
            } catch (PhotoBlogClient.ApiException error) {
                assertEquals(500, error.getStatus());
            }
            assertTrue(client.isLoggedIn());
            client.logout();
            assertFalse(client.isLoggedIn());
            assertEquals(4, server.requests().size());
        }
    }

    @Test public void alreadyRevokedTokenCanLogOutLocally() throws Exception {
        try (Stub server = new Stub(okToken(), okUser(), new Reply(401, "{}"))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            client.login("test-user", "test-password");
            client.logout();
            assertFalse(client.isLoggedIn());
            assertEquals(3, server.requests().size());
        }
    }

    @Test public void postListSendsTokenForPersonalizedRecommendationState() throws Exception {
        try (Stub server = new Stub(okToken(), okUser(), new Reply(200, "[]"))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            client.login("test-user", "test-password");
            assertTrue(client.downloadImages().isEmpty());
            List<Request> requests = server.requests();
            assertEquals("GET /api_root/Post/ HTTP/1.1", requests.get(2).line);
            assertEquals("Token " + TOKEN, requests.get(2).headers.get("authorization"));
        }
    }

    @Test public void legacyServerCannotPretendItFilteredSearchResults() throws Exception {
        try (Stub server = new Stub(new Reply(200, "[]"))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            try {
                client.downloadPosts("한글 &+%", "title");
                fail("An older server may ignore query parameters; do not label its list as search results");
            } catch (IOException error) {
                assertTrue(error.getMessage().contains("업데이트"));
            }
            Request request = server.requests().get(0);
            android.net.Uri uri = android.net.Uri.parse(server.url() + request.line.split(" ")[1]);
            assertEquals("한글 &+%", uri.getQueryParameter("search"));
            assertEquals("title", uri.getQueryParameter("search_field"));
        }
    }

    @Test public void imageDownloadsNeverReceiveAccountToken() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes));
        bitmap.recycle();
        try (Stub imageServer = new Stub(new Reply(200, bytes.toByteArray()));
             Stub apiServer = new Stub(okToken(), okUser(), new Reply(200,
                     "[{\"id\":1,\"author\":7,\"title\":\"photo\",\"image\":\"" + imageServer.url() + "/photo.png\"}]"))) {
            PhotoBlogClient client = new PhotoBlogClient(apiServer.url());
            client.login("test-user", "test-password");
            assertEquals(1, client.downloadImages().size());
            assertEquals("Token " + TOKEN, apiServer.requests().get(2).headers.get("authorization"));
            assertNull(imageServer.requests().get(0).headers.get("authorization"));
        }
    }

    @Test public void recommendationUsesPutAndDeleteWithToken() throws Exception {
        try (Stub server = new Stub(okToken(), okUser(),
                new Reply(200, "{\"id\":12,\"recommendation_count\":1,\"is_recommended\":true}"),
                new Reply(200, "{\"id\":12,\"recommendation_count\":0,\"is_recommended\":false}"))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            client.login("test-user", "test-password");
            assertTrue(client.setRecommendation(12, true).getBoolean("is_recommended"));
            assertFalse(client.setRecommendation(12, false).getBoolean("is_recommended"));
            List<Request> requests = server.requests();
            assertEquals("PUT /api_root/Post/12/recommendation/ HTTP/1.1", requests.get(2).line);
            assertEquals("DELETE /api_root/Post/12/recommendation/ HTTP/1.1", requests.get(3).line);
            assertEquals("Token " + TOKEN, requests.get(2).headers.get("authorization"));
            assertEquals("Token " + TOKEN, requests.get(3).headers.get("authorization"));
        }
    }

    @Test public void commentsUseGetPostDeleteAndToken() throws Exception {
        String comment = "{\"id\":9,\"post\":12,\"author\":7,\"author_username\":\"server-user\","
                + "\"text\":\"한글 & +\",\"created_date\":\"2026-10-19T17:00:00+09:00\",\"can_delete\":true}";
        try (Stub server = new Stub(okToken(), okUser(), new Reply(200, "[" + comment + "]"),
                new Reply(201, comment.substring(0, comment.length()-1) + ",\"comment_count\":1}"),
                new Reply(204, ""))) {
            PhotoBlogClient client = new PhotoBlogClient(server.url());
            client.login("test-user", "test-password");
            assertTrue(client.getComments(12).get(0).canDelete);
            assertEquals(9, client.createComment(12, "한글 & +").getInt("id"));
            client.deleteComment(12, 9);
            List<Request> requests = server.requests();
            assertEquals("GET /api_root/Post/12/comments/ HTTP/1.1", requests.get(2).line);
            assertEquals("POST /api_root/Post/12/comments/ HTTP/1.1", requests.get(3).line);
            assertEquals("DELETE /api_root/Post/12/comments/9/ HTTP/1.1", requests.get(4).line);
            JSONObject body = new JSONObject(requests.get(3).body);
            assertEquals(1, body.length());
            assertEquals("한글 & +", body.getString("text"));
            for (int index = 2; index <= 4; index++) {
                assertEquals("Token " + TOKEN, requests.get(index).headers.get("authorization"));
            }
        }
    }

    @Test public void sortedSearchEncodesQueryAndPreservesServerOrder() throws Exception {
        Reply response = new Reply(200, "[{\"id\":2,\"title\":\"second\"},{\"id\":1,\"title\":\"first\"}]");
        response.headers = "X-PhotoBlog-Search: 1\r\nX-PhotoBlog-Sort: comments\r\n";
        try (Stub server = new Stub(response)) {
            java.util.List<PhotoPost> posts = new PhotoBlogClient(server.url()).downloadPosts("한글 &+%", "title", "comments");
            assertEquals(2, posts.get(0).id);
            assertEquals(1, posts.get(1).id);
            Request request = server.requests().get(0);
            android.net.Uri uri = android.net.Uri.parse(server.url() + request.line.split(" ")[1]);
            assertEquals("한글 &+%", uri.getQueryParameter("search"));
            assertEquals("title", uri.getQueryParameter("search_field"));
            assertEquals("comments", uri.getQueryParameter("sort"));
            assertTrue(request.line.startsWith("GET "));
            assertEquals("", request.body);
        }
    }

    @Test public void oldOrMismatchedServerCannotPretendItSortedResults() throws Exception {
        for (String header : new String[]{"", "X-PhotoBlog-Sort: oldest\r\n"}) {
            Reply response = new Reply(200, "[]");
            response.headers = header;
            try (Stub server = new Stub(response)) {
                try {
                    new PhotoBlogClient(server.url()).downloadPosts("", "title", "newest");
                    fail("Ignoring or changing sort must not appear as sorted success");
                } catch (IOException error) {
                    assertTrue(error.getMessage().contains("정렬"));
                }
                assertEquals(1, server.requests().size());
            }
        }
    }

    @Test public void invalidSortIsRejectedBeforeOpeningNetworkConnection() throws Exception {
        try {
            new PhotoBlogClient("http://127.0.0.1:1").downloadPosts("", "title", "-password");
            fail("Reject unknown public sort values");
        } catch (IOException error) {
            assertTrue(error.getMessage().contains("정렬"));
        }
    }

    private static Reply okToken() { return new Reply(200, "{\"token\":\"" + TOKEN + "\"}"); }
    private static Reply okUser() { return new Reply(200, "{\"id\":7,\"username\":\"server-user\"}"); }
    private static final class Reply {
        final int status;
        String headers = "";
        final byte[] body;
        Reply(int status, String body) { this(status, body.getBytes(StandardCharsets.UTF_8)); }
        Reply(int status, byte[] body) { this.status = status; this.body = body; }
    }
    private static final class Request {
        String line;
        final Map<String, String> headers = new HashMap<>();
        String body;
    }
    private static final class Stub implements AutoCloseable {
        final ServerSocket socket;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<List<Request>> result;
        Stub(Reply... replies) throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(10000);
            result = executor.submit(() -> {
                List<Request> requests = new ArrayList<>();
                for (Reply reply : replies) {
                    try (Socket client = socket.accept()) {
                        client.setSoTimeout(5000);
                        InputStream input = client.getInputStream();
                        Request request = new Request();
                        request.line = line(input);
                        String header;
                        while (!(header = line(input)).isEmpty()) {
                            int colon = header.indexOf(':');
                            request.headers.put(header.substring(0, colon).toLowerCase(Locale.US),
                                    header.substring(colon + 1).trim());
                        }
                        int length = Integer.parseInt(request.headers.getOrDefault("content-length", "0"));
                        byte[] bytes = new byte[length];
                        new DataInputStream(input).readFully(bytes);
                        request.body = new String(bytes, StandardCharsets.UTF_8);
                        requests.add(request);
                        byte[] body = reply.body;
                        OutputStream output = client.getOutputStream();
                        output.write(("HTTP/1.1 " + reply.status + " Test\r\n"
                                + reply.headers + "Content-Type: application/json\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(body);
                        output.flush();
                    }
                }
                return requests;
            });
        }
        String url() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        List<Request> requests() throws Exception { return result.get(10, TimeUnit.SECONDS); }
        public void close() throws IOException { socket.close(); executor.shutdownNow(); }
        static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b;
            while ((b = input.read()) != -1 && b != '\n') if (b != '\r') bytes.write(b);
            if (b == -1) throw new EOFException();
            return bytes.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
