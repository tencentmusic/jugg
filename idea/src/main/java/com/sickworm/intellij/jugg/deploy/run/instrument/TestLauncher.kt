package com.sickworm.intellij.jugg.deploy.run.instrument

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val methodLogPidProvider: ((IDevice, ApkInfo, Logger) -> Set<Int>?)? =
        if (logcatSource is AdbTestLogcatSource) ::resolveMethodLogPids else null,
    private val logcatSinceTimeProvider: (IDevice, Logger) -> String =
        if (logcatSource is AdbTestLogcatSource) ::resolveDeviceLogcatSinceTime else { _, _ ->
            formatLogcatSinceTime(System.currentTimeMillis())
        },
    private val logcatDrainMillis: Long = if (logcatSource is AdbTestLogcatSource) 200 else 0,
    private val logcatBufferMaxLines: Int = DEFAULT_LOGCAT_BUFFER_MAX_LINES,
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
            val logAttributor = AndroidTestLogAttributor(
                maxBufferedLogcatLines = logcatBufferMaxLines,
            ) { className, testName, line ->
                resultModel.recordTestLog(deviceName, className, testName, line)
                testEventSink?.invoke(InstrumentationEvent.TestOutput(className, testName, line))
                logger.debug("[TestLog:$className#$testName] $line")
            }
            var methodLogPidResolved = false

            parser.onEvent = { event ->
                when (event) {
                    is InstrumentationEvent.TestStarted -> {
                        resultModel.recordEvent(deviceName, event)
                        testEventSink?.invoke(event)
                        renderer.render(event)
                        logAttributor.onTestStarted(event.className, event.testName)
                        if (!methodLogPidResolved) {
                            val methodLogPids = methodLogPidProvider?.invoke(device, testApk, logger)?.takeIf { it.isNotEmpty() }
                            logAttributor.setAllowedPids(methodLogPids)
                            methodLogPidResolved = true
                            logger.debug(
                                if (methodLogPids == null) {
                                    "AndroidTest method log PID filter disabled, device=$deviceName"
                                } else {
                                    "AndroidTest method log PID filter enabled, device=$deviceName, pids=$methodLogPids"
                                },
                            )
                        }
                    }
                    is InstrumentationEvent.SuiteFinished -> {
                        resultModel.recordEvent(deviceName, event)
                        testEventSink?.invoke(event)
                        renderer.render(event)
                        devicePassed = event.passed
                        deviceFailed = event.failed
                        deviceIgnored = event.ignored
                    }
                    is InstrumentationEvent.TestFinished -> {
                        logAttributor.onTestFinished(event.className, event.testName)
                        resultModel.recordEvent(deviceName, event)
                        testEventSink?.invoke(event)
                        renderer.render(event)
                        if (event.result != InstrumentationEvent.TestResult.OK &&
                            event.result != InstrumentationEvent.TestResult.IGNORED) {
                            anyFailure = true
                        }
                    }
                    is InstrumentationEvent.Aborted -> {
                        anyFailure = true
                        logAttributor.onAborted()
                        resultModel.recordEvent(deviceName, event)
                        testEventSink?.invoke(event)
                        renderer.render(event)
                    }
                    else -> {
                        resultModel.recordEvent(deviceName, event)
                        testEventSink?.invoke(event)
                        renderer.render(event)
                    }
                }
            }

            val logcatSinceTime = logcatSinceTimeProvider(device, logger)
            logger.info("AndroidTest logcat started, device=$deviceName, since=$logcatSinceTime")
            consoleOutput("[Device: $deviceName] Capturing logcat since $logcatSinceTime")
            val logcatSession = logcatSource.start(device, logger, logcatSinceTime, { line ->
                resultModel.recordLog(deviceName, line)
                logAttributor.onLogLine(line)
            }, cancelSignal)
            try {
                val exitCode = runInstrumentation(device, spec, testApk, { line ->
                    resultModel.recordLog(deviceName, line)
                    parser.feed(line)
                }, cancelSignal)
                if (exitCode != 0) {
                    logAttributor.onAborted()
                    consoleOutput("[Device: $deviceName] Instrumentation command failed with exit code $exitCode")
                    anyFailure = true
                }
            } catch (e: Exception) {
                logAttributor.onAborted()
                consoleOutput("[Device: $deviceName] Device disconnected during test run: ${e.message}")
                anyFailure = true
            } finally {
                drainLogcatBeforeClose(logcatDrainMillis)
                try {
                    logcatSession.close()
                } finally {
                    val logcatStats = logAttributor.finish()
                    logger.debug(
                        "AndroidTest logcat buffer released, device=$deviceName, " +
                                "lines=${logcatStats.lineCount}, bytes=${logcatStats.byteSize}, " +
                                "totalLines=${logcatStats.totalLineCount}, " +
                                "truncatedLines=${logcatStats.truncatedLineCount}, " +
                                "maxLines=${logcatStats.maxLines}",
                    )
                }
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
        sinceTime: String,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): AutoCloseable
}

private class AdbTestLogcatSource : TestLogcatSource {
    override fun start(
        device: IDevice,
        logger: Logger,
        sinceTime: String,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): AutoCloseable {
        val closed = AtomicBoolean(false)
        val thread = Thread({
            IdeaDeviceAdb(device, logger).execAdbShellCmdStreaming(
                "logcat -T \"$sinceTime\" -v threadtime",
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

private fun withApiLevel(name: String, api: Int): String {
    return if (api > 0 && !name.contains("API ")) "$name API $api" else name
}

private fun safeApiLevel(adb: IdeaDeviceAdb): Int {
    return runCatching { adb.api }.getOrDefault(-1)
}

internal fun formatLogcatSinceTime(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMillis))

private fun resolveDeviceLogcatSinceTime(device: IDevice, logger: Logger): String {
    val fallback = formatLogcatSinceTime(System.currentTimeMillis())
    return runCatching {
        val deviceTime = IdeaDeviceAdb(device, logger)
            .execAdbShellCmd("date '+%m-%d %H:%M:%S.000'")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
        deviceTime.takeIf { LOGCAT_SINCE_TIME_REGEX.matches(it) } ?: fallback.also {
            logger.debug("AndroidTest device logcat time invalid, output=$deviceTime, fallback=$fallback")
        }
    }.onFailure {
        logger.debug("AndroidTest device logcat time lookup failed, fallback=$fallback", it)
    }.getOrDefault(fallback)
}

private fun resolveMethodLogPids(device: IDevice, testApk: ApkInfo, logger: Logger): Set<Int>? {
    val adb = IdeaDeviceAdb(device, logger)
    return runCatching {
        methodLogPackages(testApk).flatMap { packageName ->
            parsePidList(adb.execAdbShellCmd("pidof $packageName")).ifEmpty {
                parsePsPids(adb.execAdbShellScript("ps -A | grep ${shellSingleQuote(packageName)} || true"), packageName)
            }
        }.toSet().takeIf { it.isNotEmpty() }
    }.onFailure {
        logger.debug("AndroidTest method log PID lookup failed, packages=${methodLogPackages(testApk)}", it)
    }.getOrNull()
}

private fun methodLogPackages(testApk: ApkInfo): List<String> {
    return listOfNotNull(
        testApk.instrumentationTargetPackage?.takeIf { it.isNotBlank() },
        testApk.applicationId.takeIf { it.isNotBlank() },
    ).distinct()
}

internal fun parseThreadtimePid(line: String): Int? {
    return THREADTIME_LOGCAT_REGEX.find(line)?.groupValues?.getOrNull(2)?.toIntOrNull()
}

private fun parsePidList(output: String): List<Int> {
    return output.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
}

private fun parsePsPids(output: String, packageName: String): List<Int> {
    return output.lineSequence().mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        columns.getOrNull(1)?.toIntOrNull()?.takeIf {
            columns.lastOrNull() == packageName
        }
    }.toList()
}

private fun shellSingleQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}

private fun drainLogcatBeforeClose(drainMillis: Long) {
    if (drainMillis <= 0) return
    try {
        Thread.sleep(drainMillis)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private val THREADTIME_LOGCAT_REGEX = Regex(
    "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\d+)\\s+\\d+\\s+[VDIWEAF]\\s+.*$",
)

private val LOGCAT_SINCE_TIME_REGEX = Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}$")
