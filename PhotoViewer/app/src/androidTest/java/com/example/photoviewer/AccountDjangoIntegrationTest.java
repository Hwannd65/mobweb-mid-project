package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;

/** Opt-in test against an isolated local Django DB, never the cloud production account DB. */
@RunWith(AndroidJUnit4.class)
public class AccountDjangoIntegrationTest {
    @Test public void signupLoginRotateAndLogoutThroughRealDjango() throws Exception {
        assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("accountDjangoTest", "false")));
        assumeTrue(MainActivity.SITE_URL.equals("http://10.0.2.2:8001"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PhotoBlogClient client = ((PhotoViewerApplication) context.getApplicationContext()).client();
        client.clearSession();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String username = "android_reader_" + System.currentTimeMillis();
        String password = "River!Quartz-29-Sun";
        File evidence = new File(context.getFilesDir(), "account-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btn_account)).perform(click());
            onView(withId(R.id.btn_register)).perform(click());
            onView(withId(R.id.register_username)).perform(replaceText(username), closeSoftKeyboard());
            // Capture only the form without any entered password.
            assertTrue(device.takeScreenshot(new File(evidence, "14_01_account_signup.png")));
            onView(withId(R.id.register_password)).perform(replaceText(password), closeSoftKeyboard());
            onView(withId(R.id.register_confirmation)).perform(replaceText(password), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue("Successful signup should return to the login form",
                    device.wait(Until.hasObject(By.res("com.example.photoviewer", "login_username")), 15000));
            onView(withId(R.id.login_username)).check(matches(withText(username)));
            onView(withId(R.id.login_password)).perform(replaceText(password), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            assertTrue("Server-verified account name must be displayed",
                    device.wait(Until.hasObject(By.res("com.example.photoviewer", "account_status")
                            .text(context.getString(R.string.account_signed_in, username))), 15000));
            assertTrue(client.isLoggedIn());
            scenario.recreate();
            onView(withId(R.id.account_status)).check(matches(withText(context.getString(R.string.account_signed_in, username))));
            assertTrue(device.takeScreenshot(new File(evidence, "14_02_account_login.png")));
            onView(withId(R.id.btn_save)).perform(click());
            onView(withId(R.id.upload_author_name)).check(matches(withText(context.getString(R.string.upload_as_user, username))));
            onView(withId(android.R.id.button2)).perform(click());
            onView(withId(R.id.btn_account)).perform(click());
            assertTrue("Logout must complete after server token deletion",
                    device.wait(Until.hasObject(By.res("com.example.photoviewer", "textView")
                            .text(context.getString(R.string.logout_success))), 15000));
            assertFalse(client.isLoggedIn());
            assertTrue(device.takeScreenshot(new File(evidence, "14_03_account_logout.png")));
        }
    }
}
