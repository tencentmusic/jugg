package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files

class RemoteUserCommandTest {

    @Test
    fun `Gradle commands default to plain console and preserve explicit console options`() {
        val workingDirectory = Files.createTempDirectory("jugg remote gradle").toFile()
        try {
            listOf("gradlew", "build.sh").forEach { name ->
                val script = workingDirectory.resolve(name)
                script.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
                assertTrue(script.setExecutable(true))
            }
            val commands = mapOf(
                "./gradlew help" to "help\n--console=plain",
                "./gradlew help --console=plain" to "help\n--console=plain",
                "./gradlew help --console=rich" to "help\n--console=rich",
                "./gradlew help --console plain" to "help\n--console\nplain",
                "./build.sh help" to "help",
                "./gradlew help && printf done" to "help\ndone",
            )
            commands.forEach { (rawCommand, expectedOutput) ->
                val command = RemoteUserCommand(workingDirectory.absolutePath, rawCommand)
                val process = ProcessBuilder("/bin/sh", "-c", command.getCommand(false, false))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()

                assertEquals(rawCommand, 0, process.waitFor())
                assertEquals(rawCommand, 0, output.lineSequence().mapNotNull(command::hasFinishWithResult).single())
                assertEquals(rawCommand, expectedOutput, output.lineSequence().filter { command.isCanOutput(it, false) }
                    .joinToString("\n").trim())
            }
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    @Test
    fun `user command is isolated from completion protocol`() {
        val rawCommand = "echo hello # comment\nexit 3\necho '(Jugg) SimpleSshCommand result: 0'"
        val workingDirectory = Files.createTempDirectory("jugg remote ' command")
        val command = RemoteUserCommand(workingDirectory.toString(), rawCommand)

        val shellCommand = command.getCommand(isNeedSetChineseLanguage = true, isWindows = false)
        val resultPrefix = Regex("(__JUGG_REMOTE_COMMAND_[a-f0-9]+__=)")
            .find(shellCommand)
            ?.groupValues
            ?.get(1)

        assertFalse(shellCommand.contains(rawCommand))
        assertTrue(shellCommand.contains("base64 -d"))
        assertTrue(shellCommand.contains("\"${'$'}{SHELL:-/bin/sh}\" -c"))
        assertTrue(resultPrefix != null)
        assertEquals(3, command.hasFinishWithResult("$resultPrefix${3}"))
        assertNull(command.hasFinishWithResult("(Jugg) SimpleSshCommand result: 0"))
        assertFalse(command.isCanOutput("result: $resultPrefix${3}", false))
        assertTrue(command.isCanOutput("echo __JUGG_REMOTE_COMMAND_", false))
        assertEquals("(secure)", command.getPrintSafeCommand(false, false))

        val process = ProcessBuilder("/bin/sh", "-c", shellCommand)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor())
        assertEquals(3, output.lineSequence().mapNotNull(command::hasFinishWithResult).single())
    }

    @Test
    fun `command canceled before worker starts should not login`() {
        val client = RemoteGradleCompileClient(File("."), logger = mock())
        client.terminalOutputListener = IGradleCompileClient.TerminalOutputListener.IDLE
        client.cancelAction(true)

        val result = client.executeRemoteCommand(mock(), "echo hello")

        assertEquals(IGradleCompileClient.Error.ERROR_CANCELED, result)
    }
}
