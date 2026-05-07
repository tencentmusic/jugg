package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModel
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
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
        `when`(device.version).thenReturn(AndroidVersion(35, null))
        `when`(device.getProperty("ro.product.manufacturer")).thenReturn("Google")
        `when`(device.getProperty("ro.product.model")).thenReturn("Pixel_9")
        `when`(device.isOnline).thenReturn(true)
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

class TestLauncherSmEventSinkTest {

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
    fun `run sends parser events to test event sink without changing success`() {
        val events = mutableListOf<InstrumentationEvent>()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            testEventSinkFactory = { deviceName, _ ->
                events.add(InstrumentationEvent.TestStarted("device", deviceName))
                val sink: (InstrumentationEvent) -> Unit = { event -> events.add(event) }
                sink
            },
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherSmEventSinkTest::class.java),
            runInstrumentation = { _, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                lineConsumer("INSTRUMENTATION_CODE: 1")
                0
            },
        )

        assertTrue(launcher.run())
        assertTrue(events.any { it is InstrumentationEvent.TestStarted && it.className == "com.example.FooTest" })
        assertTrue(events.any { it is InstrumentationEvent.TestFinished && it.result == InstrumentationEvent.TestResult.OK })
        assertTrue(events.any { it is InstrumentationEvent.SuiteFinished && it.passed == 1 })
    }

    @Test
    fun `run passes device display name with api level to test event sink`() {
        val device = device()
        val requestedDeviceNames = mutableListOf<String>()
        val launcher = TestLauncher(
            devices = listOf(device),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            showDeviceSuite = true,
            testEventSinkFactory = { deviceName, _ ->
                requestedDeviceNames.add(deviceName)
                null
            },
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherSmEventSinkTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            runInstrumentation = { _, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                lineConsumer("INSTRUMENTATION_CODE: 1")
                0
            },
        )

        assertTrue(launcher.run())
        assertTrue(requestedDeviceNames.contains("Pixel_9 API 35"))
    }

    @Test
    fun `run prints result matrix after multiple devices`() {
        val firstDevice = deviceWithSerial("emulator-5554")
        val secondDevice = deviceWithSerial("xiaomi-1234")
        val output = StringBuilder()
        val launcher = TestLauncher(
            devices = listOf(firstDevice, secondDevice),
            spec = spec,
            testApk = testApk,
            consoleOutput = { output.appendLine(it) },
            showDeviceSuite = true,
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherSmEventSinkTest::class.java),
            deviceDisplayName = { device, _ ->
                if (device.serialNumber == "emulator-5554") "Pixel_9 API 35" else "Xiaomi 2509 API 36"
            },
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer(if (device.serialNumber == "emulator-5554") {
                    "INSTRUMENTATION_STATUS_CODE: 0"
                } else {
                    "INSTRUMENTATION_STATUS_CODE: -2"
                })
                lineConsumer("INSTRUMENTATION_CODE: 1")
                0
            },
        )

        assertFalse(launcher.run())
        val text = output.toString()
        assertTrue(text.contains("Android Test Results Matrix"))
        assertTrue(text.contains("Test | Pixel_9 API 35 | Xiaomi 2509 API 36"))
        assertTrue(text.contains("com.example.FooTest#testBar | Pass | Fail"))
        assertTrue(text.contains("Device Detail - Pixel_9 API 35"))
        assertTrue(text.contains("Device Detail - Xiaomi 2509 API 36"))
    }

}

private fun deviceWithSerial(serial: String): IDevice {
    val device = mock(IDevice::class.java)
    `when`(device.serialNumber).thenReturn(serial)
    `when`(device.version).thenReturn(AndroidVersion(35, null))
    `when`(device.getProperty("ro.product.manufacturer")).thenReturn("Google")
    `when`(device.getProperty("ro.product.model")).thenReturn("Pixel_9")
    `when`(device.isOnline).thenReturn(true)
    return device
}

class TestLauncherResultSessionTest {

    private val spec = AndroidTestRunSpec("com.example.FooTest", "testBar")

    private val testApk = ApkInfo(
        files = listOf(ApkFileUnit("com.example.app.test", "", true, File("test.apk"))),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
    )

    @Test
    fun `sequential single-device launches with shared model should print matrix at the end`() {
        val output = StringBuilder()
        val sharedModel = AndroidTestResultModel()

        val firstLauncher = TestLauncher(
            devices = listOf(deviceWithSerial("emulator-5554")),
            spec = spec,
            testApk = testApk,
            consoleOutput = { output.appendLine(it) },
            showDeviceSuite = true,
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultSessionTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = sharedModel,
            runInstrumentation = { _, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                lineConsumer("INSTRUMENTATION_CODE: 1")
                0
            },
        )
        val secondLauncher = TestLauncher(
            devices = listOf(deviceWithSerial("xiaomi-1234")),
            spec = spec,
            testApk = testApk,
            consoleOutput = { output.appendLine(it) },
            showDeviceSuite = true,
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultSessionTest::class.java),
            deviceDisplayName = { _, _ -> "Xiaomi 2509 API 36" },
            resultModel = sharedModel,
            printAggregatedResult = true,
            runInstrumentation = { _, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: -2")
                lineConsumer("INSTRUMENTATION_CODE: 1")
                0
            },
        )

        assertTrue(firstLauncher.run())
        assertFalse(secondLauncher.run())

        val text = output.toString()
        assertTrue(text.contains("Android Test Results Matrix"))
        assertTrue(text.contains("Test | Pixel_9 API 35 | Xiaomi 2509 API 36"))
        assertTrue(text.contains("com.example.FooTest#testBar | Pass | Fail"))
        assertTrue(text.contains("Device Detail - Pixel_9 API 35"))
        assertTrue(text.contains("Device Detail - Xiaomi 2509 API 36"))
    }
}
