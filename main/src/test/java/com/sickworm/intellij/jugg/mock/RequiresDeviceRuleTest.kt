package com.sickworm.intellij.jugg.mock

import org.junit.Test
import org.junit.internal.AssumptionViolatedException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequiresDeviceRuleTest {

    @Test
    fun `online device should not start emulator`() {
        val runner = FakeRequiresDeviceCommandRunner(
            adbDevicesOutputs = mutableListOf(
                """
                List of devices attached
                emulator-5554	device
                """.trimIndent()
            ),
            avdListOutput = "Pixel_7",
        )
        val checker = RequiresDeviceChecker(runner, bootTimeoutMs = 1, pollIntervalMs = 1)

        checker.ensureDevice()

        assertEquals(emptyList(), runner.startedCommands)
    }

    @Test
    fun `missing device should start configured emulator and wait until online`() {
        val runner = FakeRequiresDeviceCommandRunner(
            adbDevicesOutputs = mutableListOf(
                "List of devices attached\n",
                "List of devices attached\n",
                """
                List of devices attached
                emulator-5554	device
                """.trimIndent(),
            ),
            avdListOutput = "Pixel_7",
        )
        val checker = RequiresDeviceChecker(
            runner = runner,
            avdName = "Pixel_7",
            bootTimeoutMs = 100,
            pollIntervalMs = 1,
        )

        checker.ensureDevice()

        assertEquals(
            listOf(listOf(runner.emulatorPath, "-avd", "Pixel_7", "-no-snapshot-load")),
            runner.startedCommands,
        )
    }

    @Test
    fun `missing device after emulator start should skip test`() {
        val runner = FakeRequiresDeviceCommandRunner(
            adbDevicesOutputs = mutableListOf("List of devices attached\n"),
            avdListOutput = "Pixel_7",
        )
        val checker = RequiresDeviceChecker(runner, bootTimeoutMs = 1, pollIntervalMs = 1)

        assertFailsWith<AssumptionViolatedException> {
            checker.ensureDevice()
        }
    }

    private class FakeRequiresDeviceCommandRunner(
        private val adbDevicesOutputs: MutableList<String>,
        private val avdListOutput: String,
    ) : RequiresDeviceCommandRunner {
        val emulatorPath = "/sdk/emulator/emulator"
        val startedCommands = mutableListOf<List<String>>()

        override fun run(command: List<String>): RequiresDeviceCommandResult {
            return when {
                command.takeLast(2) == listOf("adb", "devices") || command == listOf("adb", "devices") ->
                    RequiresDeviceCommandResult(adbDevicesOutputs.removeFirstOrNull() ?: adbDevicesOutputs.lastOrNull().orEmpty())
                command == listOf(emulatorPath, "-list-avds") ->
                    RequiresDeviceCommandResult(avdListOutput)
                else ->
                    RequiresDeviceCommandResult("")
            }
        }

        override fun start(command: List<String>) {
            startedCommands.add(command)
        }

        override fun adbPath(): String = "adb"

        override fun emulatorPath(): String = emulatorPath
    }
}
