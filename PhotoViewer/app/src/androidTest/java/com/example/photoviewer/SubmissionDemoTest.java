package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/** Opt-in screenshot walkthrough with readable sample data, against an isolated local DB only. */
@RunWith(AndroidJUnit4.class)
public class SubmissionDemoTest {
    private final String pkg="com.example.photoviewer";
    private Context context;
    private UiDevice device;
    private File evidence;
    private PhotoBlogClient client;
    private void shot(String name) {
        device.waitForIdle();
        assertTrue(device.takeScreenshot(new File(evidence,name)));
    }
    private void status(String value) {
        assertTrue(value,device.wait(Until.hasObject(By.res(pkg,"textView").text(value)),20000));
    }
    private void search(String value,int field,int count) {
        onView(withId(R.id.search_keyword)).perform(replaceText(value),closeSoftKeyboard());
        onView(withId(R.id.search_field)).perform(click());
        onData(anything()).atPosition(field).perform(click());
        onView(withId(R.id.btn_search)).perform(click());
        status(context.getString(R.string.search_success,count));
    }
    private void first(String title) {
        onView(withId(R.id.recyclerView)).check((view,error)->{
            if(error!=null)throw error;
            RecyclerView list=(RecyclerView)view;
            ImageAdapter.ImageViewHolder row=(ImageAdapter.ImageViewHolder)list.findViewHolderForAdapterPosition(0);
            assertNotNull(row);assertEquals(title,row.title.getText().toString());
        });
    }
    private void photos() throws Exception {
        // Remove only the disposable images created by earlier automated upload tests.
        Uri collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        context.getContentResolver().delete(collection,
            MediaStore.Images.Media.DISPLAY_NAME+" LIKE ? AND "+MediaStore.Images.Media.RELATIVE_PATH+" = ?",
            new String[]{"PhotoViewer_fixture_%","Pictures/PhotoViewer/"});
        for(String name:new String[]{"Korea.jpg","KIA_Tigers.png","LA_Lakers.jpg","Liverpool_FC.png"}) {
            try(Cursor found=context.getContentResolver().query(collection,new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.DISPLAY_NAME+" = ? AND "+MediaStore.Images.Media.RELATIVE_PATH+" = ?",
                new String[]{name,"Pictures/PhotoViewer/"},null)) {
                if(found!=null && found.moveToFirst())continue;
            }
            ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
            values.put(MediaStore.Images.Media.MIME_TYPE,name.endsWith(".jpg")?"image/jpeg":"image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PhotoViewer");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            Uri photo=context.getContentResolver().insert(collection,values);assertNotNull(photo);
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("demo/"+name);
                OutputStream out=context.getContentResolver().openOutputStream(photo)) {
                assertNotNull(out);MultipartBody.copy(in,out);
            }
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
            assertEquals(1,context.getContentResolver().update(photo,values,null,null));
        }
    }
    @Test public void captureReadableDemonstration() throws Exception {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("submissionDemo","false")));
        assertEquals("http://10.0.2.2:8001",MainActivity.SITE_URL);
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        client=((PhotoViewerApplication)context.getApplicationContext()).client();client.clearSession();
        evidence=new File(context.getFilesDir(),"submission-demo");assertTrue(evidence.isDirectory()||evidence.mkdirs());
        assertEquals(3,client.downloadPosts("","title").size());
        photos();
        String password=UUID.randomUUID().toString()+"!aA";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            shot("09_01_client_start.png");
            onView(withId(R.id.btn_account)).perform(click());
            onView(withId(R.id.btn_register)).perform(click());
            onView(withId(R.id.register_username)).perform(replaceText("hwannd65"),closeSoftKeyboard());
            shot("14_01_account_signup.png");
            onView(withId(R.id.register_password)).perform(replaceText(password),closeSoftKeyboard());
            onView(withId(R.id.register_confirmation)).perform(replaceText(password),closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"login_username")),15000));
            onView(withId(R.id.login_password)).perform(replaceText(password),closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"account_status").text("hwannd65님")),15000));
            assertTrue(client.isLoggedIn());shot("14_02_account_login.png");

            onView(withId(R.id.btn_save)).perform(click());
            onView(withId(R.id.upload_title)).perform(replaceText("리버풀 엠블럼"),closeSoftKeyboard());
            onView(withId(R.id.upload_text)).perform(replaceText("좋아하는 팀 로고를 올려봤습니다."),closeSoftKeyboard());
            onView(withId(R.id.btn_choose_image)).perform(click());
            UiObject2 file=device.wait(Until.findObject(By.descStartsWith("Liverpool_FC.png,")),15000);
            assertNotNull("Select the original Liverpool image",file);
            shot("11_01_upload_picker.png");file.click();
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"selected_image_name").textContains("Liverpool_FC.png")),15000));
            shot("11_02_upload_form.png");
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"textView").textStartsWith("게시 완료:")),20000));
            onView(withId(R.id.btn_load)).perform(click());status(context.getString(R.string.sync_success,4));first("리버풀 엠블럼");
            List<PhotoPost> uploaded=client.downloadPosts("리버풀","title");assertEquals(1,uploaded.size());
            assertEquals("hwannd65",uploaded.get(0).author);assertNotNull(uploaded.get(0).image);
            shot("10_02_client_local_sync_after_upload.png");

            search("리버풀",0,1);first("리버풀 엠블럼");shot("14_04_search_title.png");
            search("minsu",1,1);first("기아 타이거즈");shot("14_05_search_author.png");
            int postId=client.downloadPosts("minsu","author").get(0).id;
            onView(withId(R.id.btn_recommend)).perform(click());
            status(context.getString(R.string.recommendation_saved));
            onView(withId(R.id.recommendation_count)).check(matches(withText("추천 2")));
            assertEquals(2,client.downloadPosts("minsu","author").get(0).recommendationCount);
            shot("14_07_post_recommend.png");
            onView(withId(R.id.btn_load)).perform(click());status(context.getString(R.string.search_success,1));
            onView(withId(R.id.btn_recommend)).check(matches(withText("추천 취소")));
            onView(withId(R.id.btn_recommend)).perform(click());status(context.getString(R.string.recommendation_removed));
            onView(withId(R.id.recommendation_count)).check(matches(withText("추천 1")));
            assertEquals(1,client.downloadPosts("minsu","author").get(0).recommendationCount);
            shot("14_08_post_cancel_recommend.png");

            onView(withId(R.id.btn_comments)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"comment_input")),15000));
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"comments_status").text("댓글 3")),15000));
            onView(withId(R.id.comment_input)).perform(replaceText("로고 잘 보이네요."),closeSoftKeyboard());
            onView(withId(R.id.comment_submit)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"comments_status").textStartsWith("댓글을 등록했습니다.")),15000));
            assertEquals(4,client.getComments(postId).size());shot("14_09_comment_created.png");
            onView(allOf(withId(R.id.comment_delete),isDisplayed())).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(pkg,"comments_status").textStartsWith("댓글을 삭제했습니다.")),15000));
            assertEquals(3,client.getComments(postId).size());shot("14_10_comment_deleted.png");
            onView(withId(R.id.comments_back)).perform(click());
            status(context.getString(R.string.search_success,1));

            onView(withId(R.id.search_field)).perform(click());onData(anything()).atPosition(0).perform(click());
            onView(withId(R.id.search_keyword)).perform(replaceText(""),closeSoftKeyboard());
            String[] modes={"newest","oldest","recommendations","comments"};
            String[] expected={"리버풀 엠블럼","태극기","태극기","기아 타이거즈"};
            String[] shots={"14_12_sort_newest.png","14_13_sort_oldest.png","14_14_sort_recommendations.png","14_15_sort_comments.png"};
            for(int i=0;i<4;i++) {
                onView(withId(R.id.sort_order)).perform(click());onData(anything()).atPosition(i).perform(click());
                onView(withId(R.id.btn_sort)).perform(click());status(context.getString(R.string.sync_success,4));first(expected[i]);
                assertEquals(expected[i],client.downloadPosts("","title",modes[i]).get(0).title);
                shot(shots[i]);
            }
            onView(withId(R.id.btn_account)).perform(click());assertTrue(device.wait(Until.hasObject(By.res(pkg,"account_status").text(context.getString(R.string.account_guest))),15000));assertFalse(client.isLoggedIn());
        }
    }
}
