package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CommentDjangoIntegrationTest {
    private static final String TITLE = "댓글 검증 사진";
    private static final String PASSWORD = "River!Quartz-29-Sun";
    private static final String TEXT = "Android에서 작성한 댓글 & + 100%";
    private Context context;
    private UiDevice device;
    private PhotoBlogClient client;
    private File evidence;

    @Before public void localOnly() {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("commentDjangoTest", "false")));
        assumeTrue(MainActivity.SITE_URL.equals("http://10.0.2.2:8001"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        client = ((PhotoViewerApplication) context.getApplicationContext()).client();
        client.clearSession();
        evidence = new File(context.getFilesDir(), "comment-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }
    private String registerLogin(PhotoBlogClient target, String prefix) throws Exception {
        String user = prefix + System.currentTimeMillis();
        target.register(user, PASSWORD, PASSWORD);
        target.login(user, PASSWORD);
        return user;
    }
    private int postId() throws Exception {
        List<PhotoPost> posts = client.downloadPosts(TITLE, "title");
        assertEquals(1, posts.size());
        assertTrue(posts.get(0).commentsSupported);
        return posts.get(0).id;
    }
    private void openComments() {
        onView(withId(R.id.search_keyword)).perform(replaceText(TITLE), closeSoftKeyboard());
        onView(withId(R.id.btn_search)).perform(click());
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                .text(context.getString(R.string.search_success, 1))), 15000));
        onView(withId(R.id.btn_comments)).perform(click());
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "comments_status")
                .textContains("댓글")), 15000));
        // The refresh button becomes enabled only after the GET finishes.
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "comments_refresh")
                .enabled(true)), 15000));
    }
    private void closeComments() {
        onView(withId(R.id.comments_back)).perform(click());
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                .text(context.getString(R.string.search_success, 1))), 15000));
    }
    private void cleanupMine(PhotoBlogClient target, int postId) throws Exception {
        for (PhotoComment comment : target.getComments(postId)) {
            if (comment.canDelete) target.deleteComment(postId, comment.id);
        }
    }

    @Test public void createRefreshCancelDeleteAndConfirmDeleteOnScreen() throws Exception {
        registerLogin(client, "comment_ui_");
        int postId = postId();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openComments();
            onView(withId(R.id.comment_submit)).perform(click());
            onView(withId(R.id.comment_input)).check(matches(hasErrorText(context.getString(R.string.comment_required))));
            onView(withId(R.id.comment_input)).perform(replaceText(TEXT), closeSoftKeyboard());
            onView(withId(R.id.comment_submit)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "comments_status")
                    .textStartsWith(context.getString(R.string.comment_created))), 15000));
            onView(withId(R.id.comment_text)).check(matches(withText(TEXT)));
            onView(withId(R.id.comment_input)).check(matches(withText("")));
            onView(withId(R.id.comment_input)).check((view, error) -> {
                if (error != null) throw error;
                org.junit.Assert.assertNull(((android.widget.EditText) view).getError());
            });
            assertEquals(1, client.getComments(postId).size());
            assertTrue(device.takeScreenshot(new File(evidence, "14_09_comment_created.png")));
            onView(withId(R.id.comments_refresh)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "comments_status")
                    .text(context.getString(R.string.comments_count, 1))), 15000));
            onView(withId(R.id.comment_delete)).perform(click());
            onView(withId(android.R.id.button2)).perform(click());
            assertEquals(1, client.getComments(postId).size());
            onView(withId(R.id.comment_delete)).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "comments_status")
                    .textStartsWith(context.getString(R.string.comment_deleted))), 15000));
            assertTrue(client.getComments(postId).isEmpty());
            assertTrue(device.takeScreenshot(new File(evidence, "14_10_comment_deleted.png")));
            closeComments();
            onView(withId(R.id.btn_comments)).check(matches(withText(context.getString(R.string.comments_count, 0))));
        } finally {
            cleanupMine(client, postId);
            client.logout();
        }
    }

    @Test public void otherReadersCommentCannotBeDeletedByThisAccount() throws Exception {
        registerLogin(client, "comment_reader_");
        PhotoBlogClient other = new PhotoBlogClient(MainActivity.SITE_URL);
        registerLogin(other, "comment_owner_");
        int postId = postId();
        JSONObject created = other.createComment(postId, "다른 사용자가 작성한 댓글");
        int commentId = created.getInt("id");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openComments();
            onView(withId(R.id.comment_text)).check(matches(withText("다른 사용자가 작성한 댓글")));
            onView(withId(R.id.comment_delete)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            try {
                client.deleteComment(postId, commentId);
                fail("The server must also reject deletion outside the UI");
            } catch (PhotoBlogClient.ApiException error) {
                assertEquals(403, error.getStatus());
            }
            assertEquals(1, client.getComments(postId).size());
            closeComments();
        } finally {
            other.deleteComment(postId, commentId);
            other.logout();
            client.logout();
        }
    }

    @Test public void guestReadsAndLoginReturnsToSameCommentScreen() throws Exception {
        PhotoBlogClient owner = new PhotoBlogClient(MainActivity.SITE_URL);
        String username = registerLogin(owner, "comment_guest_");
        int postId = postId();
        int commentId = owner.createComment(postId, "로그인 전에도 읽을 수 있는 댓글").getInt("id");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openComments();
            onView(withId(R.id.comment_input)).check(matches(not(isEnabled())));
            onView(withId(R.id.comment_delete)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            assertTrue(device.takeScreenshot(new File(evidence, "14_11_comment_guest.png")));
            onView(withId(R.id.comments_login)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "login_username")), 10000));
            onView(withId(R.id.login_username)).perform(replaceText(username), closeSoftKeyboard());
            onView(withId(R.id.login_password)).perform(replaceText(PASSWORD), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "comments_status")
                    .text(context.getString(R.string.comments_count, 1))), 15000));
            onView(withId(R.id.comment_input)).check(matches(isEnabled()));
            onView(withId(R.id.comment_delete)).check(matches(isDisplayed()));
            closeComments();
        } finally {
            owner.deleteComment(postId, commentId);
            owner.logout();
            client.clearSession();
        }
    }
}
