package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class SearchLayoutUiTest {
    @Test public void footerRemainsVisibleAboveKeyboardInBothOrientations() throws Exception {
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File evidence=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"layout-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        device.setOrientationNatural();
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.search_keyword)).perform(closeSoftKeyboard());
            waitForKeyboard(scenario,false);
            checkGeometry(scenario,false);
            assertTrue(device.takeScreenshot(new File(evidence,"14_17_search_bottom.png")));
            onView(withId(R.id.search_keyword)).perform(click(),typeText("cloud"));
            waitForKeyboard(scenario,true);
            checkGeometry(scenario,true);
            assertTrue(device.takeScreenshot(new File(evidence,"14_18_search_keyboard.png")));
            onView(withId(R.id.search_keyword)).perform(closeSoftKeyboard());
            waitForKeyboard(scenario,false);
            checkGeometry(scenario,false);
            device.setOrientationLeft();
            onView(withId(R.id.search_keyword)).perform(click());
            waitForKeyboard(scenario,true);
            checkGeometry(scenario,true);
            onView(withId(R.id.search_keyword)).perform(closeSoftKeyboard());
        } finally {
            device.setOrientationNatural();
            device.unfreezeRotation();
        }
    }
    private void waitForKeyboard(ActivityScenario<MainActivity> scenario,boolean visible) {
        long deadline=SystemClock.uptimeMillis()+6000;
        AtomicBoolean ready=new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(activity.findViewById(R.id.main));
                ready.set(insets!=null && insets.isVisible(WindowInsetsCompat.Type.ime())==visible);
            });
            if(ready.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        } while(SystemClock.uptimeMillis()<deadline);
        fail("Keyboard visibility did not become "+visible);
    }
    private void checkGeometry(ActivityScenario<MainActivity> scenario,boolean keyboard) {
        scenario.onActivity(activity -> {
            View root=activity.findViewById(R.id.main);
            View panel=activity.findViewById(R.id.search_panel);
            WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(root);
            int[] origin=new int[2];
            root.getLocationOnScreen(origin);
            int bottom=origin[1]+root.getHeight()-Math.max(insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            Rect panelBounds=new Rect();
            assertTrue(panel.getGlobalVisibleRect(panelBounds));
            assertTrue("Footer must stay above the keyboard/system navigation",panelBounds.bottom<=bottom+2);
            assertTrue("Footer must stay anchored to the bottom",Math.abs(panelBounds.bottom-bottom)<=2);
            for(int id:new int[]{R.id.search_field,R.id.search_keyword,R.id.btn_search,R.id.btn_search_clear}) {
                View control=activity.findViewById(id);
                Rect rect=new Rect();
                assertTrue("Search controls must be visible",control.getGlobalVisibleRect(rect));
                assertEquals("Search controls must not be clipped",control.getHeight(),rect.height());
                assertTrue(rect.bottom<=bottom+2);
            }
            if(!keyboard) {
                Rect sort=new Rect();
                Rect list=new Rect();
                activity.findViewById(R.id.sort_order).getGlobalVisibleRect(sort);
                activity.findViewById(R.id.recyclerView).getGlobalVisibleRect(list);
                assertTrue("Sort stays above the list",sort.bottom<=list.top);
                assertTrue("List must not overlap footer",list.bottom<=panelBounds.top);
            }
        });
    }
}
