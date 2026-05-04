package com.sickworm.intellij.jugg.mock

import org.junit.rules.ExternalResource
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * JUnit 4 ClassRule that skips the entire test class when no Android device is connected.
 * Usage: companion object { @ClassRule @JvmField val deviceRule = RequiresDeviceRule() }
 */
class RequiresDeviceRule : ExternalResource() {
    override fun before() {
        RequiresDeviceChecker(RequiresDeviceSystemCommandRunner()).ensureDevice()
    }
}

/**
 * Ensures device-dependent tests have an online Android device before execution.
 */
class RequiresDeviceChecker(
    private val runner: RequiresDeviceCommandRunner,
    private val avdName: String? = System.getProperty("jugg.test.avd") ?: System.getenv("JUGG_TEST_AVD"),
    private val bootTimeoutMs: Long = System.getProperty("jugg.test.deviceBootTimeoutMs")?.toLongOrNull() ?: 120_000,
    private val pollIntervalMs: Long = 2_000,
) {
    fun ensureDevice() {
        if (hasDevice()) {
            return
        }

        val emulatorName = avdName ?: firstAvdName()
        if (emulatorName != null) {
            runner.start(listOf(runner.emulatorPath(), "-avd", emulatorName, "-no-snapshot-load"))
            waitForDevice()
        }

        Assume.assumeTrue("No Android device connected, skipping @RequiresDevice test", hasDevice())
    }

    private fun waitForDevice() {
        val deadline = System.currentTimeMillis() + bootTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDevice()) {
                return
            }
            Thread.sleep(pollIntervalMs)
        }
    }

    private fun firstAvdName(): String? {
        return runner.run(listOf(runner.emulatorPath(), "-list-avds"))
            .output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    private fun hasDevice(): Boolean {
        val output = runner.run(listOf(runner.adbPath(), "devices")).output
        return output.lines()
            .drop(1)
            .map { it.trim() }
            .any { it.endsWith("\tdevice") }
    }
}

interface RequiresDeviceCommandRunner {
    fun run(command: List<String>): RequiresDeviceCommandResult
    fun start(command: List<String>)
    fun adbPath(): String
    fun emulatorPath(): String
}

data class RequiresDeviceCommandResult(val output: String)

class RequiresDeviceSystemCommandRunner : RequiresDeviceCommandRunner {
    override fun run(command: List<String>): RequiresDeviceCommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = String(process.inputStream.readAllBytes())
        process.waitFor(30, TimeUnit.SECONDS)
        return RequiresDeviceCommandResult(output)
    }

    override fun start(command: List<String>) {
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    override fun adbPath(): String = "adb"

    override fun emulatorPath(): String {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val sdkEmulator = androidHome?.let { File(it, "emulator/emulator") }
        if (sdkEmulator != null && sdkEmulator.exists()) {
            return sdkEmulator.path
        }
        return "emulator"
    }
}
