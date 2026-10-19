package com.example.photoviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LoginUiTest {
    @Test public void loginFormRejectsMissingFieldsWithoutNetwork() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.account_status)).check(matches(withText(R.string.account_guest)));
            onView(withId(R.id.btn_account)).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.login_username)).check(matches(hasErrorText("필수 입력 항목입니다.")));
            onView(withId(R.id.login_username)).perform(replaceText("test-user"));
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.login_password)).check(matches(hasErrorText("필수 입력 항목입니다.")));
            onView(withId(android.R.id.button2)).perform(click());
            onView(allOf(withId(R.id.btn_account), withText(R.string.login))).check(matches(isDisplayed()));
        }
    }
}
