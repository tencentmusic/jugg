package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationConsoleRenderer
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationOutputParser

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
            val deviceName = adb.displayName
            consoleOutput("[Device: $deviceName] Running tests...")

            val parser = InstrumentationOutputParser()
            val renderer = InstrumentationConsoleRenderer(consoleOutput)
            val testEventSink = testEventSinkFactory(deviceName, showDeviceSuite)
            var devicePassed = 0
            var deviceFailed = 0
            var deviceIgnored = 0

            parser.onEvent = { event ->
                testEventSink?.invoke(event)
                renderer.render(event)
                when (event) {
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
                    }
                    is InstrumentationEvent.Aborted -> anyFailure = true
                    else -> Unit
                }
            }

            try {
                val exitCode = runInstrumentation(device, spec, testApk, parser::feed, cancelSignal)
                if (exitCode != 0) {
                    consoleOutput("[Device: $deviceName] Instrumentation command failed with exit code $exitCode")
                    anyFailure = true
                }
            } catch (e: Exception) {
                consoleOutput("[Device: $deviceName] Device disconnected during test run: ${e.message}")
                anyFailure = true
            }

            if (deviceFailed > 0) anyFailure = true
            globalPassed += devicePassed
            globalFailed += deviceFailed
            globalIgnored += deviceIgnored

            consoleOutput("[Device: $deviceName] Tests summary: $devicePassed passed, $deviceFailed failed, $deviceIgnored ignored")
        }

        if (devices.size > 1) {
            consoleOutput("All devices: ${devices.size} devices, $globalPassed passed, $globalFailed failed, $globalIgnored ignored.")
        }

        return !anyFailure
    }
}
