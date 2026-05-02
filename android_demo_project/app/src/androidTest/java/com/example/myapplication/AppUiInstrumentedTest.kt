package com.example.myapplication

import android.content.Intent
import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUiInstrumentedTest {
    @Test
    fun mainActivityShowsTitle() {
        Log.i("AppUiInstrumentedTest", "[mainActivityShowsTitle] in")
        launchMainActivity().use {
            onView(withText("Hello World Jugg!")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun mainActivityShowsNavigationButton() {
        Log.i("AppUiInstrumentedTest", "[mainActivityShowsNavigationButton] in")
        launchMainActivity().use {
            onView(withId(R.id.button)).check(matches(withText("MainActivity2")))
        }
    }

    private fun launchMainActivity(): AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        return AutoCloseable { activity.finish() }
    }
}
