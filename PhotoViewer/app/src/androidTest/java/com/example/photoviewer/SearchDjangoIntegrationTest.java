package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.anything;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SearchDjangoIntegrationTest {
    private Context context;
    private UiDevice device;
    private File evidence;

    @Before public void requireLocalFixture() {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("searchDjangoTest", "false")));
        assumeTrue(MainActivity.SITE_URL.equals("http://10.0.2.2:8001"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        evidence = new File(context.getFilesDir(), "search-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }

    @Test public void koreanAndReservedCharactersSurviveQueryEncoding() throws Exception {
        List<PhotoPost> posts = new PhotoBlogClient(MainActivity.SITE_URL)
                .downloadPosts("서울 산책 & 카페+100%_", "title");
        assertEquals(1, posts.size());
        assertEquals("서울 산책 & 카페+100%_", posts.get(0).title);
        assertNotNull(posts.get(0).image);
        assertTrue(posts.get(0).image.getWidth() > 0);
    }

    @Test public void searchFieldsEmptyResultsAndResetThroughRealDjango() throws Exception {
        int allCount = new PhotoBlogClient(MainActivity.SITE_URL).downloadPosts("", "title").size();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            enter("서울 산책", 0);
            waitForCount(1);
            assertFirstTitle("서울 산책 & 카페+100%_");
            assertTrue(device.takeScreenshot(new File(evidence, "14_04_search_title.png")));
            enter("search_author", 1);
            waitForCount(1);
            assertFirstTitle("바다 여행");
            assertTrue(device.takeScreenshot(new File(evidence, "14_05_search_author.png")));
            enter("없는검색어123", 0);
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                    .text(context.getString(R.string.search_empty))), 15000));
            assertCount(0);
            assertTrue(device.takeScreenshot(new File(evidence, "14_06_search_empty.png")));
            onView(withId(R.id.btn_search_clear)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                    .text(context.getString(R.string.sync_success, allCount))), 20000));
            assertCount(allCount);
        }
    }

    private void enter(String keyword, int field) {
        onView(withId(R.id.search_keyword)).perform(replaceText(keyword), closeSoftKeyboard());
        onView(withId(R.id.search_field)).perform(click());
        onData(anything()).atPosition(field).perform(click());
        onView(withId(R.id.btn_search)).perform(click());
    }
    private void waitForCount(int count) {
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                .text(context.getString(R.string.search_success, count))), 20000));
        assertCount(count);
    }
    private void assertCount(int expected) {
        onView(withId(R.id.recyclerView)).check((view, error) -> {
            if (error != null) throw error;
            assertEquals(expected, ((RecyclerView) view).getAdapter().getItemCount());
        });
    }
    private void assertFirstTitle(String expected) {
        onView(withId(R.id.recyclerView)).check((view, error) -> {
            if (error != null) throw error;
            ImageAdapter.ImageViewHolder holder = (ImageAdapter.ImageViewHolder)
                    ((RecyclerView) view).findViewHolderForAdapterPosition(0);
            assertNotNull(holder);
            assertEquals(expected, holder.title.getText().toString());
        });
    }
}
