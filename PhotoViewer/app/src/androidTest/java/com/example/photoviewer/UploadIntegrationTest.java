package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.ContentValues;
import android.graphics.Rect;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Explicit integration tests: creates one local Django post only when enabled. */
@RunWith(AndroidJUnit4.class)
public class UploadIntegrationTest {
    private static final String PACKAGE = "com.example.photoviewer";
    private static final String TITLE = "Android UI 업로드 검증";
    private Context context;
    private UiDevice device;
    private File evidence;

    @Before
    public void requireExplicitIntegrationRun() throws Exception {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("djangoUploadTest", "false")));
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        evidence = new File(context.getExternalFilesDir(null), "upload-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        // A connected test may reinstall the app, so request the real runtime permission again.
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btn_load)).perform(click());
            UiObject2 allow = device.wait(Until.findObject(By.res(
                    "com.android.permissioncontroller", "permission_allow_button")), 2000);
            if (allow != null) allow.click();
            assertTrue("Django GET must work before testing POST",
                    device.wait(Until.hasObject(By.res(PACKAGE, "textView")
                            .text(Pattern.compile("이미지 [0-9]+개 동기화 완료"))), 15000));
        }
    }

    @Test
    public void selectImageAndUploadToDjango() throws Exception {
        String filename = "PhotoViewer_fixture_" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoViewer");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri photo = context.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(photo);
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("upload-fixture.png");
             OutputStream output = context.getContentResolver().openOutputStream(photo)) {
            assertNotNull(output);
            MultipartBody.copy(input, output);
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        assertEquals(1, context.getContentResolver().update(photo, values, null, null));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btn_save)).perform(click());
            onView(withId(R.id.upload_title)).perform(replaceText(TITLE), closeSoftKeyboard());
            onView(withId(R.id.upload_text)).perform(replaceText(
                    "Android에서 이미지를 직접 선택해 multipart POST로 게시했습니다."),
                    closeSoftKeyboard());
            onView(withId(R.id.btn_choose_image)).perform(click());

            UiObject2 file = device.wait(Until.findObject(By.descStartsWith(filename + ",")), 15000);
            if (file == null) {
                saveHierarchy("picker-missing.xml");
                saveScreenshot("picker-missing.png");
            }
            assertNotNull("The real system image picker must show the seeded photo", file);
            saveScreenshot("11_upload_picker.png");
            Rect bounds = file.getVisibleBounds();
            assertTrue(device.click(bounds.centerX(), bounds.centerY()));
            boolean selected = device.wait(Until.hasObject(
                    By.res(PACKAGE, "selected_image_name").text(filename)), 10000);
            if (!selected) {
                saveHierarchy("picker-return.xml");
                saveScreenshot("picker-return.png");
            }
            assertTrue("The selected image URI must return to the upload form", selected);
            saveScreenshot("11_upload_form.png");
            onView(withText("게시")).perform(click());

            UiObject2 allow = device.wait(Until.findObject(By.res(
                    "com.android.permissioncontroller", "permission_allow_button")), 1500);
            if (allow != null) allow.click();
            boolean success = device.wait(Until.hasObject(
                    By.res(PACKAGE, "textView").textStartsWith("게시 완료:")), 20000);
            if (!success) {
                saveHierarchy("upload-failed.xml");
                saveScreenshot("upload-failed.png");
            }
            assertTrue("Django must accept the real multipart upload with HTTP 201", success);
            assertTrue(device.findObject(By.res(PACKAGE, "textView")).getText().contains(TITLE));
            saveScreenshot("11_upload_success.png");

            onView(withId(R.id.btn_load)).perform(click());
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "textView")
                    .text(Pattern.compile("이미지 [0-9]+개 동기화 완료"))), 20000));
            saveScreenshot("10_client_sync_after_upload.png");
        }
    }

    private void saveScreenshot(String name) throws Exception {
        File file = new File(evidence, name);
        assertTrue("Screenshot must be captured", device.takeScreenshot(file));

    }

    private void saveHierarchy(String name) throws Exception {
        File file = new File(evidence, name);
        device.dumpWindowHierarchy(file);

    }

    @After
    public void publishCapturedEvidence() throws Exception {
        // Publishing screenshots while the picker is open would reorder its photo grid.
        if (context == null || evidence == null) return;
        File[] files = evidence.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().endsWith(".png")) {
                publishEvidence(file, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "image/png", "Pictures/PhotoViewerEvidence");
            } else if (file.getName().endsWith(".xml")) {
                publishEvidence(file, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "application/xml", "Download/PhotoViewerEvidence");
            }
            if (!file.delete()) file.deleteOnExit();
        }
    }

    private void publishEvidence(File file, Uri collection, String mime, String relativePath)
            throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(collection, values);
        assertNotNull(uri);
        try (InputStream input = new FileInputStream(file);
             OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull(output);
            MultipartBody.copy(input, output);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(uri, values, null, null);
    }

    @Test
    public void invalidImageIsRejectedByDjango() throws Exception {
        File fake = File.createTempFile("invalid-image-", ".png", context.getCacheDir());
        try {
            try (OutputStream output = new FileOutputStream(fake)) {
                output.write("this is not a real image".getBytes(StandardCharsets.UTF_8));
            }
            try {
                new PhotoBlogClient("http://10.0.2.2:8000").uploadPost(context,
                        Uri.fromFile(fake), 1, "거절되어야 하는 Android 이미지",
                        "이미지가 아닌 파일은 저장되면 안 됩니다.");
                fail("An invalid image must not receive HTTP 201");
            } catch (PhotoBlogClient.ApiException error) {
                assertEquals(400, error.getStatus());
                assertTrue(new JSONObject(error.getResponseBody()).has("image"));
            }
        } finally {
            if (!fake.delete()) fake.deleteOnExit();
        }
    }
}
