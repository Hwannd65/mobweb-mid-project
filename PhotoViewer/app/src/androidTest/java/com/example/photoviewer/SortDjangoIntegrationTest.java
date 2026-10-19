package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.anything;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import android.widget.Spinner;
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
public class SortDjangoIntegrationTest {
    private static final String QUERY = "정렬 검증";
    private static final String A = "정렬 검증 · A 오래된 사진";
    private static final String B = "정렬 검증 · B 댓글 많은 사진";
    private static final String C = "정렬 검증 · C 새로운 사진";
    private static final String D = "정렬 검증 · D 같은 시각 사진";
    private static final String[] SORTS = {"newest", "oldest", "recommendations", "comments"};
    private Context context;
    private UiDevice device;
    private PhotoBlogClient client;
    private File evidence;

    @Before public void localOnly() {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("sortDjangoTest", "false")));
        assumeTrue(MainActivity.SITE_URL.equals("http://10.0.2.2:8001"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        client = ((PhotoViewerApplication) context.getApplicationContext()).client();
        client.clearSession();
        evidence = new File(context.getFilesDir(), "sort-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }

    @Test public void realApiReturnsEveryModeInServerOrder() throws Exception {
        String[][] expected = {{D,C,B,A}, {A,B,C,D}, {A,C,B,D}, {B,C,A,D}};
        for (int index=0; index<SORTS.length; index++) {
            List<PhotoPost> posts = client.downloadPosts(QUERY, "title", SORTS[index]);
            assertEquals(4, posts.size());
            for (int row=0; row<4; row++) assertEquals(expected[index][row], posts.get(row).title);
            PhotoPost a = posts.stream().filter(p -> p.title.equals(A)).findFirst().get();
            assertEquals(2, a.recommendationCount);
            assertEquals(1, a.commentCount);
        }
        List<PhotoPost> authored = client.downloadPosts("sort_fixture_author", "author", "comments");
        assertEquals(3, authored.size());
        assertEquals(B, authored.get(0).title);
        assertTrue(client.downloadPosts("정렬검증없는검색어", "title", "oldest").isEmpty());
    }

    @Test public void sortControlsSearchResetAndRecreationUseSelectedOrder() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.search_keyword)).perform(replaceText(QUERY), closeSoftKeyboard());
            String[] first = {D,A,A,B};
            String[] screenshots = {"14_12_sort_newest.png", "14_13_sort_oldest.png", "14_14_sort_recommendations.png", "14_15_sort_comments.png"};
            for (int index=0; index<4; index++) {
                select(index);
                waitResult(4, true);
                assertFirst(first[index]);
                assertTrue(device.takeScreenshot(new File(evidence, screenshots[index])));
            }
            scenario.recreate();
            onView(withId(R.id.sort_order)).check((view,error) -> {
                if (error != null) throw error;
                assertEquals(3, ((Spinner)view).getSelectedItemPosition());
            });
            onView(withId(R.id.btn_load)).perform(click());
            waitResult(4, true);
            assertFirst(B);
            int total = client.downloadPosts("", "title", "comments").size();
            String firstGlobal = client.downloadPosts("", "title", "comments").get(0).title;
            onView(withId(R.id.btn_search_clear)).perform(click());
            waitResult(total, false);
            assertFirst(firstGlobal);
            onView(withId(R.id.sort_order)).check((view,error) -> {
                if (error != null) throw error;
                assertEquals(3, ((Spinner)view).getSelectedItemPosition());
            });
        }
    }

    @Test public void recommendationChangeRefreshesRankingAutomatically() throws Exception {
        String username = "sort_ui_" + System.currentTimeMillis();
        String password = "River!Quartz-29-Sun";
        client.register(username,password,password);
        client.login(username,password);
        int changed = 0;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            List<PhotoPost> posts = client.downloadPosts(QUERY,"title","recommendations");
            changed = posts.get(1).id;
            assertEquals(C,posts.get(1).title);
            onView(withId(R.id.search_keyword)).perform(replaceText(QUERY),closeSoftKeyboard());
            select(2);
            waitResult(4,true);
            assertFirst(A);
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.recyclerView);
                ImageAdapter.ImageViewHolder row = (ImageAdapter.ImageViewHolder)list.findViewHolderForAdapterPosition(1);
                assertNotNull(row);
                assertEquals(C,row.title.getText().toString());
                assertTrue(row.recommendButton.isEnabled());
                row.recommendButton.performClick();
            });
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer","post_title").text(C)),15000));
            assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer","btn_sort").enabled(true)),15000));
            waitResult(4,true);
            assertFirst(C);
            PhotoPost first = client.downloadPosts(QUERY,"title","recommendations").get(0);
            assertEquals(C,first.title);
            assertTrue(first.recommended);
            assertEquals(2,first.recommendationCount);
        } finally {
            if(changed>0) client.setRecommendation(changed,false);
            client.logout();
        }
    }

    private void select(int index) {
        onView(withId(R.id.sort_order)).perform(click());
        onData(anything()).atPosition(index).perform(click());
        onView(withId(R.id.btn_sort)).perform(click());
    }
    private void waitResult(int count,boolean searching) {
        assertTrue(device.wait(Until.hasObject(By.res("com.example.photoviewer","textView")
                .text(context.getString(searching?R.string.search_success:R.string.sync_success,count))),20000));
    }
    private void assertFirst(String expected) {
        onView(withId(R.id.recyclerView)).check((view,error) -> {
            if(error!=null) throw error;
            ImageAdapter.ImageViewHolder row=(ImageAdapter.ImageViewHolder)((RecyclerView)view).findViewHolderForAdapterPosition(0);
            assertNotNull(row);
            assertEquals(expected,row.title.getText().toString());
        });
    }
}
