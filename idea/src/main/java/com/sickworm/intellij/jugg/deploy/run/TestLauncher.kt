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
import com.sickworm.intellij.jugg.logger.JuggLogger

/**
 * TestLauncher runs `am instrument` against a list of devices sequentially and aggregates results.
 *
 * Each device gets a labelled section in the console output. The overall run fails if any test on
 * any device fails. Phase 1 uses pure log-stream output; Phase 3 will extend this to SM Test Runner.
 */
class TestLauncher(
    private val devices: List<IDevice>,
    private val spec: AndroidTestRunSpec,
    private val testApk: ApkInfo,
    private val consoleOutput: (String) -> Unit,
    private val cancelSignal: () -> Boolean,
    private val logger: Logger,
) {

    /** Returns true when all tests on all devices passed. */
    fun run(): Boolean {
        var globalPassed = 0
        var globalFailed = 0
        var globalIgnored = 0
        var anyFailure = false

        devices.forEachIndexed { index, device ->
            val adb = IdeaDeviceAdb(device, logger)
            val deviceName = adb.displayName ?: device.serialNumber
            consoleOutput("[Device: $deviceName] Running tests...")

            val parser = InstrumentationOutputParser()
            val renderer = InstrumentationConsoleRenderer(consoleOutput)
            var devicePassed = 0
            var deviceFailed = 0
            var deviceIgnored = 0

            parser.onEvent = { event ->
                renderer.render(event)
                when (event) {
                    is InstrumentationEvent.SuiteFinished -> {
                        devicePassed = event.passed
                        deviceFailed = event.failed
                        deviceIgnored = event.ignored
                    }
                    is InstrumentationEvent.Aborted -> anyFailure = true
                    else -> Unit
                }
            }

            try {
                AdbCmdHelper(adb, logger).runInstrumentation(spec, testApk, parser::feed, cancelSignal)
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
