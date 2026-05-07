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
import org.junit.Assert.assertEquals
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
    fun `logcat lines between test started and finished are recorded for that method`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: inside")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                0
            },
        )

        assertTrue(launcher.run())
        assertTrue(model.testLogDetail("com.example.FooTest", "testBar").contains("Foo: inside"))
    }

    @Test
    fun `single device multiple methods do not leak logcat between methods`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testA")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: a")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testA")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testB")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:59.756 1234 1234 I Foo: b")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testB")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                0
            },
        )

        assertTrue(launcher.run())
        val detailA = model.testLogDetail("com.example.FooTest", "testA")
        val detailB = model.testLogDetail("com.example.FooTest", "testB")
        assertTrue(detailA.contains("Foo: a"))
        assertFalse(detailA.contains("Foo: b"))
        assertTrue(detailB.contains("Foo: b"))
        assertFalse(detailB.contains("Foo: a"))
    }

    @Test
    fun `logcat outside active method does not enter any method log`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                logcatSource.emit(device, "05-07 15:31:57.756 1234 1234 I Foo: before")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: inside")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                logcatSource.emit(device, "05-07 15:31:59.756 1234 1234 I Foo: after")
                0
            },
        )

        assertTrue(launcher.run())
        val detail = model.testLogDetail("com.example.FooTest", "testBar")
        assertTrue(detail.contains("Foo: inside"))
        assertFalse(detail.contains("Foo: before"))
        assertFalse(detail.contains("Foo: after"))
    }

    @Test
    fun `multi device logcat does not cross devices`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val firstDevice = deviceWithSerial("emulator-5554")
        val secondDevice = deviceWithSerial("xiaomi-1234")
        val launcher = TestLauncher(
            devices = listOf(firstDevice, secondDevice),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { device, _ ->
                if (device.serialNumber == "emulator-5554") "Pixel_9 API 35" else "Xiaomi 2509 API 36"
            },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                val message = if (device.serialNumber == "emulator-5554") "pixel" else "xiaomi"
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: $message")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                0
            },
        )

        assertTrue(launcher.run())
        val detail = model.testLogDetail("com.example.FooTest", "testBar")
        assertTrue(detail.contains("Pixel_9 API 35: Pass"))
        assertTrue(detail.contains("Foo: pixel"))
        assertTrue(detail.contains("Xiaomi 2509 API 36: Pass"))
        assertTrue(detail.contains("Foo: xiaomi"))
        assertEquals(1, Regex("Foo: pixel").findAll(detail).count())
        assertEquals(1, Regex("Foo: xiaomi").findAll(detail).count())
        assertTrue(detail.indexOf("Pixel_9 API 35") < detail.indexOf("Foo: pixel"))
        assertTrue(detail.indexOf("Foo: pixel") < detail.indexOf("Xiaomi 2509 API 36"))
        assertTrue(detail.indexOf("Xiaomi 2509 API 36") < detail.indexOf("Foo: xiaomi"))
    }


    @Test
    fun `aborted run preserves active method logcat`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: before abort")
                lineConsumer("INSTRUMENTATION_ABORTED: Process crashed.")
                logcatSource.emit(device, "05-07 15:31:59.756 1234 1234 I Foo: after abort")
                0
            },
        )

        assertFalse(launcher.run())
        val detail = model.testLogDetail("com.example.FooTest", "testBar")
        assertTrue(detail.contains("Foo: before abort"))
        assertFalse(detail.contains("Foo: after abort"))
    }

    @Test
    fun `nonzero instrumentation exit keeps completed method logcat`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val launcher = TestLauncher(
            devices = listOf(device()),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: completed before failed exit")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                logcatSource.emit(device, "05-07 15:31:59.756 1234 1234 I Foo: after completed method")
                -1
            },
        )

        assertFalse(launcher.run())
        val detail = model.testLogDetail("com.example.FooTest", "testBar")
        assertTrue(detail.contains("Foo: completed before failed exit"))
        assertFalse(detail.contains("Foo: after completed method"))
    }

    @Test
    fun `nonzero instrumentation exit closes active method logcat window`() {
        val model = AndroidTestResultModel()
        val logcatSource = FakeTestLogcatSource()
        val testDevice = device()
        logcatSource.emitOnClose(testDevice, "05-07 15:31:59.756 1234 1234 I Foo: after failed exit")
        val launcher = TestLauncher(
            devices = listOf(testDevice),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherResultTest::class.java),
            deviceDisplayName = { _, _ -> "Pixel_9 API 35" },
            resultModel = model,
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: before failed exit")
                -1
            },
        )

        assertFalse(launcher.run())
        val detail = model.testLogDetail("com.example.FooTest", "testBar")
        assertTrue(detail.contains("Foo: before failed exit"))
        assertFalse(detail.contains("Foo: after failed exit"))
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
    fun `run sends active logcat lines to test event sink as test output`() {
        val logcatSource = FakeTestLogcatSource()
        val events = mutableListOf<InstrumentationEvent>()
        val testDevice = device()
        val launcher = TestLauncher(
            devices = listOf(testDevice),
            spec = spec,
            testApk = testApk,
            consoleOutput = {},
            testEventSinkFactory = { _, _ ->
                val sink: (InstrumentationEvent) -> Unit = { event -> events.add(event) }
                sink
            },
            cancelSignal = { false },
            logger = Logger.getInstance(TestLauncherSmEventSinkTest::class.java),
            logcatSource = logcatSource,
            runInstrumentation = { device, _, _, lineConsumer, _ ->
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 1")
                logcatSource.emit(device, "05-07 15:31:58.756 1234 1234 I Foo: inside")
                lineConsumer("INSTRUMENTATION_STATUS: class=com.example.FooTest")
                lineConsumer("INSTRUMENTATION_STATUS: test=testBar")
                lineConsumer("INSTRUMENTATION_STATUS_CODE: 0")
                0
            },
        )

        assertTrue(launcher.run())
        assertTrue(events.any {
            it is InstrumentationEvent.TestOutput &&
                    it.className == "com.example.FooTest" &&
                    it.testName == "testBar" &&
                    it.text.contains("Foo: inside")
        })
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


private class FakeTestLogcatSource : TestLogcatSource {
    private val consumers = linkedMapOf<String, (String) -> Unit>()
    private val closeLines = linkedMapOf<String, String>()

    override fun start(
        device: IDevice,
        logger: Logger,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): AutoCloseable {
        consumers[device.serialNumber] = lineConsumer
        return AutoCloseable {
            closeLines[device.serialNumber]?.let { lineConsumer(it) }
            consumers.remove(device.serialNumber)
        }
    }

    fun emit(device: IDevice, line: String) {
        consumers[device.serialNumber]?.invoke(line)
    }

    fun emitOnClose(device: IDevice, line: String) {
        closeLines[device.serialNumber] = line
    }
}
