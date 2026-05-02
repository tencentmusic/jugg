package com.example.library1

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.sickworm.jugg.demo.testcase.databinding.library1.DataBindingJavaDemoActivityLibrary1
import com.sickworm.jugg.demo.testcase.databinding.library1.DataBindingKotlinDemoActivityLibrary1
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Library1UiInstrumentedTest {
    @Test
    fun javaDataBindingActivityShowsUserName() {
        Log.i("Library1UiInstrumentedTest", "[javaDataBindingActivityShowsUserName] in")
        launchActivity(DataBindingJavaDemoActivityLibrary1::class.java).use {
            onView(withText("Jugg User")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun kotlinDataBindingActivityShowsUserAge() {
        Log.i("Library1UiInstrumentedTest", "[kotlinDataBindingActivityShowsUserAge] in")
        launchActivity(DataBindingKotlinDemoActivityLibrary1::class.java).use {
            onView(withText("44")).check(matches(isDisplayed()))
        }
    }

    private fun launchActivity(activityClass: Class<out Activity>): AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, activityClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        return AutoCloseable { activity.finish() }
    }
}
