package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JschShellTerminalHelperTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun respondToCursorPositionQuery() {
        val buffer = StringBuilder("\u001B[6n")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.CURSOR_POSITION_REPORT, response)
        assertEquals("", buffer.toString())
    }

    @Test
    fun respondToOsc11QueryWithStTerminator() {
        val buffer = StringBuilder("\u001B]11;?\u001B\\")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.BACKGROUND_COLOR_REPORT, response)
        assertEquals("", buffer.toString())
    }

    @Test
    fun respondToOsc11QueryWithBelTerminator() {
        val buffer = StringBuilder("\u001B]11;?\u0007")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.BACKGROUND_COLOR_REPORT, response)
        assertEquals("", buffer.toString())
    }

    @Test
    fun respondToQueriesBeforePromptLikeUserCancelOutput() {
        val buffer = StringBuilder("\u001B]11;?\u001B\\\u001B[6n[root@host ~]# ")
        var responses = mutableListOf<String>()
        while (true) {
            val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer) ?: break
            responses.add(response)
        }
        assertEquals(
            listOf(
                JschShellTerminalHelper.BACKGROUND_COLOR_REPORT,
                JschShellTerminalHelper.CURSOR_POSITION_REPORT,
            ),
            responses,
        )
        assertEquals("[root@host ~]# ", buffer.toString())
    }

    @Test
    fun respondToOsc11QueryWithoutTerminatorBeforePrompt() {
        val buffer = StringBuilder("\u001B]11;?[root@yangggzhou-2ix20jwbsa ~]# ")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.BACKGROUND_COLOR_REPORT, response)
        assertEquals("[root@yangggzhou-2ix20jwbsa ~]# ", buffer.toString())
    }

    @Test
    fun incompleteOsc11QueryDoesNotRespond() {
        val buffer = StringBuilder("\u001B]11;?")
        assertNull(JschShellTerminalHelper.tryRespondTerminalQuery(buffer))
        assertEquals("\u001B]11;?", buffer.toString())
    }

    @Test
    fun parseShellReadyResultLine() {
        assertEquals(0, JschShellTerminalHelper.parseShellReadyResult("(Jugg) ShellReady result: 0"))
        assertNull(JschShellTerminalHelper.parseShellReadyResult("(Jugg) ShellReady result: \$?"))
        assertNull(JschShellTerminalHelper.parseShellReadyResult("(Jugg) MkDirCommand result: 0"))
    }

    @Test
    fun shellReadyProbeCommandUsesSingleLineEcho() {
        assertTrue(JschShellTerminalHelper.SHELL_READY_PROBE_COMMAND.contains("(Jugg) ShellReady result: \$?"))
        assertTrue(!JschShellTerminalHelper.SHELL_READY_PROBE_COMMAND.contains("\n"))
    }

    @Test
    fun shellEchoDisableCommandUsesSingleLineAndParsesResult() {
        assertTrue(JschShellTerminalHelper.DISABLE_SHELL_ECHO_COMMAND.startsWith("stty -echo"))
        assertTrue(!JschShellTerminalHelper.DISABLE_SHELL_ECHO_COMMAND.contains("\n"))
        assertEquals(0, JschShellTerminalHelper.parseShellEchoDisabledResult("(Jugg) ShellEchoDisabled result: 0"))
        assertNull(
            JschShellTerminalHelper.parseShellEchoDisabledResult(
                "(Jugg) ShellEchoDisabled result: \$__jugg_exit"
            )
        )
    }

    @Test
    fun passwordLoginSuccessDoesNotSearchAvailableKeys() {
        val originalUserHome = System.getProperty("user.home")
        val sshDir = temporaryFolder.root.resolve(".ssh").also { it.mkdirs() }
        sshDir.resolve("id_test").writeText("-----BEGIN PRIVATE KEY-----\n")
        System.setProperty("user.home", temporaryFolder.root.absolutePath)

        val session = mock<Session>()
        val shell = mock<ChannelShell>()
        val shellOutput = ByteArrayOutputStream()
        val logger = mock<Logger>()
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.remoteSshPassword).thenReturn("password")
        whenever(options.remoteSshUser).thenReturn("user")
        whenever(options.remoteSshIp).thenReturn("host")
        whenever(options.remoteSshPort).thenReturn(22)
        whenever(options.httpProxyIp).thenReturn("")
        whenever(options.environmentVariables).thenReturn("")
        whenever(session.getConfig("PubkeyAcceptedAlgorithms")).thenReturn("ssh-ed25519")
        whenever(session.openChannel("shell")).thenReturn(shell)
        whenever(shell.inputStream).thenReturn(
            ByteArrayInputStream(
                "(Jugg) ShellReady result: 0\n(Jugg) ShellEchoDisabled result: 0\n".toByteArray()
            ),
        )
        whenever(shell.outputStream).thenReturn(shellOutput)
        whenever(shell.isConnected).thenReturn(true)
        whenever(shell.isClosed).thenReturn(false)

        try {
            Mockito.mockConstruction(JSch::class.java) { jsch, _ ->
                whenever(jsch.getSession(any(), any(), any())).thenReturn(session)
            }.use {
                val remoteClient = RemoteGradleCompileClient(temporaryFolder.root, logger = logger)
                remoteClient.login(options)
                remoteClient.dispose()
            }

            verify(logger, never()).debug(argThat<String> { startsWith("found keys in .ssh") })
            assertTrue(shellOutput.toString().contains(JschShellTerminalHelper.DISABLE_SHELL_ECHO_COMMAND))
        } finally {
            System.setProperty("user.home", originalUserHome)
        }
    }

    @Test
    fun passwordLoginFailureSearchesAvailableKeysBeforeFallback() {
        val originalUserHome = System.getProperty("user.home")
        val sshDir = temporaryFolder.root.resolve(".ssh").also { it.mkdirs() }
        sshDir.resolve("id_test").writeText("-----BEGIN PRIVATE KEY-----\n")
        System.setProperty("user.home", temporaryFolder.root.absolutePath)

        val session = mock<Session>()
        val logger = mock<Logger>()
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.remoteSshPassword).thenReturn("password")
        whenever(options.remoteSshUser).thenReturn("user")
        whenever(options.remoteSshIp).thenReturn("host")
        whenever(options.remoteSshPort).thenReturn(22)
        whenever(options.httpProxyIp).thenReturn("")
        whenever(session.getConfig("PubkeyAcceptedAlgorithms")).thenReturn("ssh-ed25519")
        whenever(session.connect()).thenThrow(JSchException("Auth fail"))

        try {
            Mockito.mockConstruction(JSch::class.java) { jsch, _ ->
                whenever(jsch.getSession(any(), any(), any())).thenReturn(session)
            }.use {
                val remoteClient = RemoteGradleCompileClient(temporaryFolder.root, logger = logger)
                assertFailsWith<JuggException> { remoteClient.login(options) }
                remoteClient.dispose()
            }

            verify(logger, atLeastOnce()).debug(argThat<String> { startsWith("found keys in .ssh") })
        } finally {
            System.setProperty("user.home", originalUserHome)
        }
    }
}
