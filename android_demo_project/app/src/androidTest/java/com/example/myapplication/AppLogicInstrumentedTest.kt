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
        Log.i("AppLogicInstrumentedTest", "[targetContextUsesAppPackage] in")
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("com.example.myapplication", context.packageName)
    }

    @Test
    fun appNameComesFromTargetResources() {
        Log.i("AppLogicInstrumentedTest", "[appNameComesFromTargetResources] in")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = context.getString(R.string.app_name)

        assertTrue(appName.contains("Application"))
    }

    @Test
    fun extrasReceivesBenchmarkModeAndTimeout() {
        Log.i("AppLogicInstrumentedTest", "[extrasReceivesBenchmarkModeAndTimeout] in")
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("benchmark_mode"))
        assertEquals("5000", args.getString("timeout"))
    }

    @Test
    fun extrasHandlesSpecialCharacters() {
        Log.i("AppLogicInstrumentedTest", "[extrasHandlesSpecialCharacters] in")
        val args = InstrumentationRegistry.getArguments()
        assertEquals("name=foo;bar", args.getString("filter"))
        assertEquals("smoke;regression", args.getString("tags"))
    }
}
