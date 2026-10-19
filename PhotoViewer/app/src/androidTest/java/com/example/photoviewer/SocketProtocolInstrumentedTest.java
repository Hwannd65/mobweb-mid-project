package com.example.photoviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Explicit integration test: requires the local Socket Server's response.bin (JSON []). */
@RunWith(AndroidJUnit4.class)
public class SocketProtocolInstrumentedTest {
    @Test
    public void emptyJsonResponseReturnsEmptyImageList() throws Exception {
        assumeTrue("Enable socketProtocolTest only while the JSON Socket Server is running",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("socketProtocolTest", "false")));
        if (Build.VERSION.SDK_INT >= 37) {
            assertEquals("Allow PhotoViewer's local network access before this test",
                    PackageManager.PERMISSION_GRANTED,
                    ContextCompat.checkSelfPermission(
                            InstrumentationRegistry.getInstrumentation().getTargetContext(),
                            Manifest.permission.ACCESS_LOCAL_NETWORK));
        }
        // Runs on the instrumentation worker thread, using the app's real HTTP client.
        List<Bitmap> images = new PhotoBlogClient("http://10.0.2.2:8000").downloadImages();
        assertEquals(0, images.size());
    }
}
