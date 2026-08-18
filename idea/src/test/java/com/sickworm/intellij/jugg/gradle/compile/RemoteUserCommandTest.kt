package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.nio.file.Files

class RemoteUserCommandTest {

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
        val client = RemoteGradleCompileClient(mock(), logger = mock())
        client.terminalOutputListener = IGradleCompileClient.TerminalOutputListener.IDLE
        client.cancelAction(true)

        val result = client.executeRemoteCommand(mock(), "echo hello")

        assertEquals(IGradleCompileClient.Error.ERROR_CANCELED, result)
    }
}
