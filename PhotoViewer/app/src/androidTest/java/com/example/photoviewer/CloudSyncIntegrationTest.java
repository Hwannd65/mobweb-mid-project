package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Explicit, read-only integration test against the deployed PythonAnywhere server. */
@RunWith(AndroidJUnit4.class)
public class CloudSyncIntegrationTest {
    private static final String CLOUD_URL = "https://hwannd65mid.pythonanywhere.com";

    @Test
    public void syncButtonDisplaysCloudImages() throws Exception {
        assumeTrue("Enable cloudSyncTest only when the deployed server is available",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("cloudSyncTest", "false")));
        assertEquals("The installed app must target the deployed server",
                CLOUD_URL, MainActivity.SITE_URL);
        int expectedImages = expectedCloudImages();
        assertTrue("Publish an image post before running cloud verification", expectedImages > 0);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btn_load)).perform(click());
            assertTrue("The real sync button must finish downloading the cloud images",
                    device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                            .text(context.getString(R.string.sync_success, expectedImages))), 30000));
            onView(withId(R.id.recyclerView)).check((view, error) -> {
                if (error != null) throw error;
                RecyclerView list = (RecyclerView) view;
                assertNotNull(list.getAdapter());
                assertEquals(expectedImages, list.getAdapter().getItemCount());
                ImageAdapter.ImageViewHolder first = (ImageAdapter.ImageViewHolder)
                        list.findViewHolderForAdapterPosition(0);
                assertNotNull("The first cloud image must be visible", first);
                assertTrue(first.imageView.getDrawable() instanceof BitmapDrawable);
                BitmapDrawable drawable = (BitmapDrawable) first.imageView.getDrawable();
                assertTrue(drawable.getBitmap().getWidth() > 0);
                assertTrue(drawable.getBitmap().getHeight() > 0);
            });
            File evidence = new File(context.getFilesDir(), "cloud-evidence");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            assertTrue(device.takeScreenshot(new File(evidence, "13_client_cloud_sync.png")));
            Log.i("CloudSyncTest", "Verified displayed cloud images=" + expectedImages);
        }
    }

    private int expectedCloudImages() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(CLOUD_URL + "/api_root/Post/").openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        try {
            assertEquals(HttpURLConnection.HTTP_OK, connection.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray posts = new JSONArray(body.toString());
            int images = 0;
            for (int i = 0; i < posts.length(); i++) {
                String image = posts.getJSONObject(i).optString("image", "");
                if (image.isEmpty() || "null".equals(image)) continue;
                URL url = new URL(image);
                assertEquals("https", url.getProtocol());
                assertEquals("hwannd65mid.pythonanywhere.com", url.getHost());
                images++;
            }
            return images;
        } finally {
            connection.disconnect();
        }
    }
}
