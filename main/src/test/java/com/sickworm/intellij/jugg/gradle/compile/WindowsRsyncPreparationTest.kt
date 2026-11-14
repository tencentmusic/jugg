package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jcraft.jsch.*
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.io.*

class WindowsRsyncPreparationTest {
    @Test
    fun `sharing conflict recovers before transfer after three seconds`() {
        runScenario(listOf(-1073741757, 0), expectedSuccess = true, expectedAttempts = 2)
    }

    @Test
    fun `second failure is reported without transfer`() {
        runScenario(listOf(-1073741757, -1073741757), expectedSuccess = false, expectedAttempts = 2)
    }

    @Test
    fun `ordinary conversion failure is not retried`() {
        runScenario(listOf(1), expectedSuccess = false, expectedAttempts = 1)
    }

    @Test
    fun `normal preparation transfers immediately`() {
        runScenario(listOf(0), expectedSuccess = true, expectedAttempts = 1)
    }

    @Test
    fun `cancellation during retry wait prevents preparation and transfer`() {
        runScenario(listOf(-1073741757, 0), expectedSuccess = false, expectedAttempts = 1, cancel = true)
    }

    @Test
    fun `environment path conversion also recovers before transfer`() {
        runScenario(listOf(-1073741757, 0), expectedSuccess = true, expectedAttempts = 2, environmentFailure = true)
    }

    private var attempts = 0
    private var firstAt = 0L
    private var secondAt = 0L
    private var cancelThread: Thread? = null
    private var lastFailure: WindowsRsyncSetupException? = null

    private fun runScenario(
        results: List<Int>, expectedSuccess: Boolean, expectedAttempts: Int,
        cancel: Boolean = false, environmentFailure: Boolean = false,
    ) {
        org.junit.Assume.assumeTrue(com.sickworm.intellij.jugg.compiler.isWindows)
        org.junit.Assume.assumeTrue(System.getenv("PATH").split(File.pathSeparator).any {
            File(it.trim('"'), "rsync.exe").isFile
        })
        val session = mock<Session>()
        val replies = configureShell(session)
        val failures = mutableListOf<WindowsRsyncSetupException>()
        val client = RemoteGradleCompileClient(mock<Project>(), logger = mock<Logger>())
        client.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) = Unit
            override fun onOutputErr(line: String) = Unit
            override fun onWindowsRsyncSetupFailure(failure: WindowsRsyncSetupException) { failures += failure }
        }
        Mockito.mockConstruction(JSch::class.java) { jsch, _ ->
            whenever(jsch.getSession(any(), any(), any())).thenReturn(session)
        }.use {
            Mockito.mockConstruction(WindowsRsyncRuntime::class.java) { runtime, _ ->
                whenever(runtime.arguments(any())).thenAnswer {
                    if (environmentFailure) listOf("rsync") else prepare(results, client, cancel)
                }
                whenever(runtime.environment(any())).thenAnswer {
                    if (environmentFailure) prepare(results, client, cancel)
                    emptyMap<String, String>()
                }
                whenever(runtime.invoke(any(), any(), anyOrNull())).thenReturn(0)
            }.use { runtimes ->
                try {
                    client.login(options())
                    assertEquals(expectedSuccess, client.fetchClasspathResult(emptyList()) != null)
                    assertEquals(expectedAttempts, attempts)
                    verify(runtimes.constructed().single(), times(if (expectedSuccess) 1 else 0))
                        .invoke(any(), any(), anyOrNull())
                    assertEquals(if (expectedSuccess || cancel) 0 else 1, failures.size)
                    if (failures.isNotEmpty()) assertSame(lastFailure, failures.single())
                    if (attempts == 2) assertTrue((secondAt - firstAt) / 1_000_000 >= 3000)
                } finally {
                    cancelThread?.join(2000)
                    client.dispose()
                    replies.close()
                }
            }
        }
    }

    private fun prepare(results: List<Int>, client: RemoteGradleCompileClient, cancel: Boolean): List<String> {
        attempts++
        if (attempts == 1) firstAt = System.nanoTime() else secondAt = System.nanoTime()
        val code = results[attempts - 1]
        if (code == 0) return listOf("rsync")
        if (cancel) cancelThread = Thread { Thread.sleep(200); client.cancelAction(false) }.also { it.start() }
        val failure = WindowsRsyncSetupException("path_conversion_failed", "D:/cygwin64/bin/cygpath.exe",
            safeReason = "Exit code: $code; invalid conversion result", exitCode = code)
        lastFailure = failure
        throw failure
    }

    private fun options(): JuggGradleCompileOptions = mock<JuggGradleCompileOptions>().also { options ->
        whenever(options.remoteSshPassword).thenReturn("password")
        whenever(options.remoteSshUser).thenReturn("user")
        whenever(options.remoteSshIp).thenReturn("host")
        whenever(options.remoteSshPort).thenReturn(22)
        whenever(options.httpProxyIp).thenReturn("")
        whenever(options.environmentVariables).thenReturn("")
        whenever(options.remoteSyncPath).thenReturn("/remote")
        whenever(options.finalRemoteSyncPath).thenReturn("/remote")
        whenever(options.remoteSyncRootRsyncPath).thenReturn("user@host:/remote/")
        whenever(options.remoteToLocalRootRsyncPath).thenReturn("D:/classpath/")
        whenever(options.remoteToLocalSyncClasspathPath).thenReturn("D:/classpath/project")
        whenever(options.syncMode).thenReturn(SyncMode.RSYNC_SIMPLE)
    }

    private fun configureShell(session: Session): PipedOutputStream {
        val shell = mock<ChannelShell>()
        whenever(session.getConfig("PubkeyAcceptedAlgorithms")).thenReturn("ssh-ed25519")
        whenever(session.isConnected).thenReturn(true)
        whenever(session.openChannel("shell")).thenReturn(shell)
        val input = PipedInputStream()
        val replies = PipedOutputStream(input)
        replies.write("(Jugg) ShellReady result: 0\n".toByteArray())
        whenever(shell.inputStream).thenReturn(input)
        whenever(shell.outputStream).thenReturn(object : ByteArrayOutputStream() {
            override fun flush() {
                if (toString().contains("SimpleSshCommand result:")) {
                    replies.write("(Jugg) SimpleSshCommand result: 0\n".toByteArray())
                    replies.flush()
                }
                reset()
            }
        })
        whenever(shell.isConnected).thenReturn(true)
        whenever(shell.isClosed).thenReturn(false)
        return replies
    }
}
