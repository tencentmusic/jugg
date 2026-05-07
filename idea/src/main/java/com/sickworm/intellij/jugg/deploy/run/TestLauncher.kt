package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestDeviceInfo
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModel
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationConsoleRenderer
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationOutputParser
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TestLauncher runs `am instrument` against devices and forwards structured test events to optional UI sinks.
 */
class TestLauncher(
    private val devices: List<IDevice>,
    private val spec: AndroidTestRunSpec,
    private val testApk: ApkInfo,
    private val consoleOutput: (String) -> Unit,
    private val showDeviceSuite: Boolean = true,
    private val testEventSinkFactory: (String, Boolean) -> ((InstrumentationEvent) -> Unit)? = { _, _ -> null },
    private val cancelSignal: () -> Boolean,
    private val logger: Logger,
    private val deviceDisplayName: (IDevice, IdeaDeviceAdb) -> String = { device, adb ->
        val api = safeApiLevel(adb)
        withApiLevel(adb.displayName.orEmpty().ifBlank { device.serialNumber }, api)
    },
    private val resultModel: AndroidTestResultModel = AndroidTestResultModel(),
    private val printAggregatedResult: Boolean = devices.size > 1,
    private val logcatSource: TestLogcatSource = AdbTestLogcatSource(),
    private val runInstrumentation: (
        device: IDevice,
        runSpec: AndroidTestRunSpec,
        runTestApk: ApkInfo,
        lineConsumer: (String) -> Unit,
        isCanceled: () -> Boolean,
    ) -> Int = { device, runSpec, runTestApk, lineConsumer, isCanceled ->
        AdbCmdHelper(IdeaDeviceAdb(device, logger), logger)
            .runInstrumentation(runSpec, runTestApk, lineConsumer, isCanceled)
    },
) {

    /** Returns true when all tests on all devices passed. */
    fun run(): Boolean {
        var globalPassed = 0
        var globalFailed = 0
        var globalIgnored = 0
        var anyFailure = false

        devices.forEach { device ->
            val adb = IdeaDeviceAdb(device, logger)
            val deviceName = deviceDisplayName(device, adb)
            val api = safeApiLevel(adb)
            resultModel.startDevice(AndroidTestDeviceInfo(adb.serial, deviceName, api.takeIf { it > 0 }))
            consoleOutput("[Device: $deviceName] Running tests...")

            val parser = InstrumentationOutputParser()
            val renderer = InstrumentationConsoleRenderer(consoleOutput)
            val testEventSink = testEventSinkFactory(deviceName, showDeviceSuite)
            var devicePassed = 0
            var deviceFailed = 0
            var deviceIgnored = 0
            val activeTestLock = Any()
            var activeTest: ActiveTest? = null

            parser.onEvent = { event ->
                resultModel.recordEvent(deviceName, event)
                testEventSink?.invoke(event)
                renderer.render(event)
                when (event) {
                    is InstrumentationEvent.TestStarted -> synchronized(activeTestLock) {
                        activeTest = ActiveTest(event.className, event.testName)
                    }
                    is InstrumentationEvent.SuiteFinished -> {
                        devicePassed = event.passed
                        deviceFailed = event.failed
                        deviceIgnored = event.ignored
                    }
                    is InstrumentationEvent.TestFinished -> {
                        if (event.result != InstrumentationEvent.TestResult.OK &&
                            event.result != InstrumentationEvent.TestResult.IGNORED) {
                            anyFailure = true
                        }
                        synchronized(activeTestLock) {
                            if (activeTest?.matches(event.className, event.testName) == true) {
                                activeTest = null
                            }
                        }
                    }
                    is InstrumentationEvent.Aborted -> {
                        anyFailure = true
                        synchronized(activeTestLock) {
                            activeTest = null
                        }
                    }
                    else -> Unit
                }
            }

            val logcatSession = logcatSource.start(device, logger, { line ->
                resultModel.recordLog(deviceName, line)
                val test = synchronized(activeTestLock) { activeTest }
                if (test != null) {
                    resultModel.recordTestLog(deviceName, test.className, test.testName, line)
                    testEventSink?.invoke(InstrumentationEvent.TestOutput(test.className, test.testName, line))
                }
            }, cancelSignal)
            try {
                val exitCode = runInstrumentation(device, spec, testApk, { line ->
                    resultModel.recordLog(deviceName, line)
                    parser.feed(line)
                }, cancelSignal)
                if (exitCode != 0) {
                    consoleOutput("[Device: $deviceName] Instrumentation command failed with exit code $exitCode")
                    anyFailure = true
                }
            } catch (e: Exception) {
                consoleOutput("[Device: $deviceName] Device disconnected during test run: ${e.message}")
                anyFailure = true
            } finally {
                synchronized(activeTestLock) {
                    activeTest = null
                }
                logcatSession.close()
            }

            if (deviceFailed > 0) anyFailure = true
            globalPassed += devicePassed
            globalFailed += deviceFailed
            globalIgnored += deviceIgnored

            consoleOutput("[Device: $deviceName] Tests summary: $devicePassed passed, $deviceFailed failed, $deviceIgnored ignored")
        }

        if (printAggregatedResult) {
            val matrix = resultModel.matrix()
            consoleOutput("All devices: ${matrix.devices.size} devices, $globalPassed passed, $globalFailed failed, $globalIgnored ignored.")
            consoleOutput(resultModel.matrixText())
            matrix.devices.forEach { device ->
                val detail = resultModel.deviceDetail(device.name)
                consoleOutput("Device Detail - ${device.name}")
                consoleOutput(detail.deviceInfo)
            }
        }

        return !anyFailure
    }
}

/**
 * TestLogcatSource starts one logcat stream for a device and reports device log lines to the caller.
 */
interface TestLogcatSource {
    fun start(
        device: IDevice,
        logger: Logger,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): AutoCloseable
}

private class AdbTestLogcatSource : TestLogcatSource {
    override fun start(
        device: IDevice,
        logger: Logger,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): AutoCloseable {
        val closed = AtomicBoolean(false)
        val thread = Thread({
            IdeaDeviceAdb(device, logger).execAdbShellCmdStreaming(
                "logcat -v threadtime",
                lineConsumer,
            ) { closed.get() || cancelSignal() }
        }, "Jugg AndroidTest logcat ${device.serialNumber}")
        thread.isDaemon = true
        thread.start()
        return AutoCloseable {
            closed.set(true)
            thread.interrupt()
        }
    }
}

private data class ActiveTest(val className: String, val testName: String) {
    fun matches(className: String, testName: String): Boolean {
        return this.className == className && this.testName == testName
    }
}

private fun withApiLevel(name: String, api: Int): String {
    return if (api > 0 && !name.contains("API ")) "$name API $api" else name
}

private fun safeApiLevel(adb: IdeaDeviceAdb): Int {
    return runCatching { adb.api }.getOrDefault(-1)
}
