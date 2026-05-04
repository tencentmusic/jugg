package com.example.myapplication

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLogicInstrumentedTest {
    @Test
    fun targetContextUsesAppPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("com.example.myapplication", context.packageName)
    }

    @Test
    fun appNameComesFromTargetResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = context.getString(R.string.app_name)

        assertTrue(appName.contains("Application"))
    }

    @Test
    fun incrementalAndroidTestMarker() {
        Log.i("JuggAndroidTest", "JUGG_ANDROID_TEST_INCREMENTAL_MARKER_V2")
    }
}
