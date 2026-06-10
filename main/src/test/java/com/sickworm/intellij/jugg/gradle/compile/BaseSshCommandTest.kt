package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BaseSshCommandTest {

    @Test
    fun getCommand_shouldPreserveFailedExitCode_whenUnixCommandFails() {
        val command = TestCommand("false")
        val process = ProcessBuilder("/bin/bash", "-c", command.getCommand(false, false)).start()
        val result = process.inputStream.bufferedReader().lineSequence()
            .mapNotNull { command.hasFinishWithResult(it) }
            .firstOrNull()

        process.waitFor()

        assertNotNull(result)
        assertEquals(1, result)
    }

    @Test
    fun hasFinishWithResult_shouldIgnoreEchoedUnixExitVariableTemplate() {
        val command = TestCommand("true")

        val result = command.hasFinishWithResult("(Jugg) TestCommand result: \$__jugg_exit\"")

        assertNull(result)
    }

    @Test
    fun hasFinishWithResult_shouldParseNumericResult() {
        val command = TestCommand("true")

        val result = command.hasFinishWithResult("(Jugg) TestCommand result: 0")

        assertEquals(0, result)
    }

    private class TestCommand(
        override val baseCommand: String,
    ) : BaseSshCommand()
}
