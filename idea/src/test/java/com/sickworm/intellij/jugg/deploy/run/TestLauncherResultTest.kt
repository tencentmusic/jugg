package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class TestLauncherResultTest {

    private val spec = AndroidTestRunSpec("com.example.FooTest", "testBar")

    private val testApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app.test", "", true, File("test.apk"))),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
    )

    private fun device(): IDevice {
        val device = mock(IDevice::class.java)
        `when`(device.serialNumber).thenReturn("emulator-5554")
        return device
    }

    @Test
    fun `run returns false when instrumentation reports failed test`() {
        val output = StringBuilder()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = { output.appendLine(it) },
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            runInstrumentation = { _, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: -2")
                lineConsumer("INSTRUMENTATION_CODE: 0")
                0
            },
        )

        assertFalse(launcher.run())
        assertTrue(output.toString().contains("FAILURE"))
    }

    @Test
    fun `run returns false when streaming command exits nonzero`() {
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            runInstrumentation = { _, _, _, _, _ -> -1 },
        )

        assertFalse(launcher.run())
    }
}
