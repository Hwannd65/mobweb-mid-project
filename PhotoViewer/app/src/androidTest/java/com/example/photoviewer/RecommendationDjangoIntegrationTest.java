package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
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

/** Actual PUT/DELETE requests against an isolated local Django DB; never mutates the cloud. */
@RunWith(AndroidJUnit4.class)
public class RecommendationDjangoIntegrationTest {
    private Context context;
    private UiDevice device;
    private PhotoBlogClient client;
    private File evidence;
    private static final String TITLE = "추천 검증 사진";
    private static final String PASSWORD = "River!Quartz-29-Sun";

    @Before public void localServerOnly() {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("recommendationDjangoTest", "false")));
        assumeTrue(MainActivity.SITE_URL.equals("http://10.0.2.2:8001"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        client = ((PhotoViewerApplication) context.getApplicationContext()).client();
        client.clearSession();
        evidence = new File(context.getFilesDir(), "recommendation-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }
    private void createAndLogin(PhotoBlogClient target, String label) throws Exception {
        String user = label + System.currentTimeMillis();
        target.register(user, PASSWORD, PASSWORD);
        target.login(user, PASSWORD);
    }
    private PhotoPost post(PhotoBlogClient target) throws Exception {
        List<PhotoPost> posts = target.downloadPosts(TITLE, "title");
        assertEquals(1, posts.size());
        assertTrue(posts.get(0).recommendationSupported);
        return posts.get(0);
    }

    @Test public void retriesAndTwoUsersPreserveIndependentRecommendations() throws Exception {
        PhotoBlogClient first = new PhotoBlogClient(MainActivity.SITE_URL);
        PhotoBlogClient second = new PhotoBlogClient(MainActivity.SITE_URL);
        createAndLogin(first, "recommend_first_");
        createAndLogin(second, "recommend_second_");
        int id = post(first).id;
        try {
            assertEquals(1, first.setRecommendation(id, true).getInt("recommendation_count"));
            assertEquals(1, first.setRecommendation(id, true).getInt("recommendation_count"));
            assertTrue(post(first).recommended);
            assertFalse(post(second).recommended);
            assertEquals(2, second.setRecommendation(id, true).getInt("recommendation_count"));
            assertEquals(1, first.setRecommendation(id, false).getInt("recommendation_count"));
            assertEquals(1, first.setRecommendation(id, false).getInt("recommendation_count"));
            assertTrue(post(second).recommended);
            assertEquals(0, second.setRecommendation(id, false).getInt("recommendation_count"));
        } finally {
            first.setRecommendation(id, false);
            second.setRecommendation(id, false);
            first.logout();
            second.logout();
        }
    }

    @Test public void recommendRefreshCancelAndAccountSwitchOnScreen() throws Exception {
        createAndLogin(client, "recommend_ui_");
        int id = post(client).id;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            searchFixture();
            onView(withId(R.id.recommendation_count)).check(matches(withText("추천 0")));
            onView(withId(R.id.btn_recommend)).perform(click());
            waitForStatus(R.string.recommendation_saved);
            onView(withId(R.id.recommendation_count)).check(matches(withText("추천 1")));
            onView(withId(R.id.btn_recommend)).check(matches(withText(R.string.cancel_recommendation)));
            assertTrue(device.takeScreenshot(new File(evidence, "14_07_post_recommend.png")));
            onView(withId(R.id.btn_load)).perform(click());
            waitForResults();
            onView(withId(R.id.btn_recommend)).check(matches(withText(R.string.cancel_recommendation)));
            onView(withId(R.id.recommendation_count)).check(matches(withText("추천 1")));
            onView(withId(R.id.btn_recommend)).perform(click());
            waitForStatus(R.string.recommendation_removed);
            onView(withId(R.id.recommendation_count)).check(matches(withText("추천 0")));
            assertTrue(device.takeScreenshot(new File(evidence, "14_08_post_cancel_recommend.png")));
            onView(withId(R.id.btn_account)).perform(click());
            waitForResults();
            assertFalse(client.isLoggedIn());
            onView(withId(R.id.btn_recommend)).check(matches(withText(R.string.recommend)));
        } finally {
            if (client.isLoggedIn()) {
                client.setRecommendation(id, false);
                client.logout();
            }
        }
    }

    @Test public void guestRecommendationPromptsLoginWithoutWriting() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            searchFixture();
            int before = post(client).recommendationCount;
            onView(withId(R.id.btn_recommend)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "login_username")), 5000));
            onView(withId(android.R.id.button2)).perform(click());
            assertEquals(before, post(client).recommendationCount);
            assertFalse(client.isLoggedIn());
        }
    }
    private void searchFixture() {
        onView(withId(R.id.search_keyword)).perform(replaceText(TITLE), closeSoftKeyboard());
        onView(withId(R.id.btn_search)).perform(click());
        waitForResults();
    }
    private void waitForResults() {
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                .text(context.getString(R.string.search_success, 1))), 15000));
    }
    private void waitForStatus(int resource) {
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                .text(context.getString(resource))), 15000));
    }
}
