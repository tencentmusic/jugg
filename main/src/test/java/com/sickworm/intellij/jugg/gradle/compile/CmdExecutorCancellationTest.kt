package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.mockito.Mockito
import java.nio.file.Files
import kotlin.concurrent.thread

class CmdExecutorCancellationTest {

    @Test
    fun `release before process creation prevents command start`() {
        val testDir = Files.createTempDirectory("jugg_cmd_pending_cancel").toFile()
        val marker = testDir.resolve("started")
        val executor = CmdExecutor(Mockito.mock(Logger::class.java))

        try {
            executor.prepareForInvoke()
            executor.release()
            val result = executor.invoke(SimpleSshCommand("echo started > ${shellQuote(marker.path)}"))

            assertEquals(IGradleCompileClient.Error.ERROR_CANCELED, result)
            assertFalse(marker.exists())
        } finally {
            executor.release()
            testDir.deleteRecursively()
        }
    }

    @Test
    fun `release terminates shell descendants`() {
        assumeFalse(isWindows)
        val testDir = Files.createTempDirectory("jugg_cmd_cancel").toFile()
        val childPidFile = testDir.resolve("child.pid")
        val executor = CmdExecutor(Mockito.mock(Logger::class.java))
        val worker = thread {
            executor.invoke(SimpleSshCommand(
                "sleep 30 & child=\$!; echo \$child > ${shellQuote(childPidFile.path)}; wait \$child",
            ))
        }

        try {
            assertTrue("Child process did not start", waitUntil { childPidFile.isFile && childPidFile.length() > 0 })
            val childPid = childPidFile.readText().trim().toLong()

            executor.release()
            worker.join(5_000)
            waitUntil { ProcessHandle.of(childPid).map { !it.isAlive }.orElse(true) }

            assertFalse("Command thread is still running", worker.isAlive)
            assertFalse("Child process is still running", ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
        } finally {
            executor.release()
            worker.join(5_000)
            childPidFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() }
            }
            testDir.deleteRecursively()
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
