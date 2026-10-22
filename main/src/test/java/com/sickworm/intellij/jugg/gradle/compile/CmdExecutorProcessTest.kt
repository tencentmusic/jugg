package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files

class CmdExecutorProcessTest {
    @Test
    fun `internal commands hide successful output but preserve failed diagnostics and transfer output`() {
        val root = Files.createTempDirectory("jugg quiet process").toFile()
        try {
            val source = File(root, "Probe.java").apply { writeText("""
                class Probe {
                    public static void main(String[] args) {
                        System.out.println("stdout");
                        System.err.println("stderr");
                        System.exit(Integer.parseInt(args[0]));
                    }
                }
            """.trimIndent()) }
            val listener = Mockito.mock(IGradleCompileClient.TerminalOutputListener::class.java)
            val logger = Mockito.mock(Logger::class.java)
            val executor = CmdExecutor(logger, listener)
            val java = File(System.getProperty("java.home"), "bin/java").path
            val output = mutableListOf<String>()
            assertEquals(0, executor.invokeProcess(listOf(java, source.path, "0"),
                outputCollector = output, quietOnSuccess = true))
            assertEquals(setOf("stdout", "stderr"), output.toSet())
            Mockito.verifyNoInteractions(listener)
            Mockito.verify(logger, Mockito.never()).warn(org.mockito.kotlin.any<String>(), org.mockito.kotlin.anyOrNull<Throwable>())
            assertEquals(23, executor.invokeProcess(listOf(java, source.path, "23"), quietOnSuccess = true))
            Mockito.verify(listener).onOutput("stdout", true)
            Mockito.verify(listener).onOutput("stderr", false)
            Mockito.clearInvocations(listener)
            assertEquals(0, executor.invokeProcess(listOf(java, source.path, "0")))
            Mockito.verify(listener).onOutput("stdout", true)
            Mockito.verify(listener).onOutput("stderr", false)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `silent native exit is recorded and logging failure preserves its result`() {
        val root = Files.createTempDirectory("jugg silent exit").toFile()
        try {
            val source = File(root, "Probe.java").apply {
                writeText("class Probe { public static void main(String[] args) { System.exit(23); } }")
            }
            val javaExecutable = File(System.getProperty("java.home"), "bin/java").path
            val logger = Mockito.mock(Logger::class.java)
            val arguments = listOf(javaExecutable, source.path)
            assertEquals(23, CmdExecutor(logger).invokeProcess(arguments))
            val messages = org.mockito.ArgumentCaptor.forClass(String::class.java)
            Mockito.verify(logger, Mockito.atLeastOnce()).debug(messages.capture())
            val result = messages.allValues.single { it.contains("exitCode=") }
            assertTrue(result.contains("exitCode=23"))
            assertTrue(result.contains("hex=0x17"))
            assertTrue(Regex(" pid=\\d+").containsMatchIn(result))
            assertTrue(result.contains("elapsedMs="))
            assertTrue(result.contains("canceled=false"))
            Mockito.doThrow(IllegalStateException("logger unavailable")).`when`(logger)
                .debug(org.mockito.kotlin.any<String>())
            assertEquals(23, CmdExecutor(logger).invokeProcess(arguments))
            assertThrows(java.io.IOException::class.java) {
                CmdExecutor(logger).invokeProcess(listOf("missing-jugg-probe-executable"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `native startup failure remains observable without exposing arguments`() {
        val logger = Mockito.mock(Logger::class.java)
        val failure = assertThrows(java.io.IOException::class.java) {
            CmdExecutor(logger).invokeProcess(listOf("missing-jugg-probe-executable", "secret-argument"),
                mapOf("JUGG_RSYNC_PASSWORD" to "secret-password"))
        }
        val messages = org.mockito.ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(logger, Mockito.atLeastOnce()).debug(messages.capture())
        val text = messages.allValues.joinToString("\n")
        assertTrue(text.contains("missing-jugg-probe-executable"))
        assertTrue(text.contains("IOException"))
        assertFalse(text.contains("secret-argument"))
        assertFalse(text.contains("secret-password"))
        assertNotNull(failure)
    }

    @Test
    fun `direct process preserves argv and nonzero exit without completion marker`() {
        val root = Files.createTempDirectory("jugg direct process").toFile()
        try {
            val source = File(root, "Probe.java")
            source.writeText("""
                class Probe {
                    public static void main(String[] args) {
                        System.out.println(args[0]);
                        System.err.println("diagnostic");
                        System.exit(23);
                    }
                }
            """.trimIndent())
            val output = mutableListOf<String>()
            val java = File(System.getProperty("java.home"), "bin/java").path
            val result = CmdExecutor(Mockito.mock(Logger::class.java)).invokeProcess(
                listOf(java, source.path, "space & (test)"), outputCollector = output,
            )
            assertEquals(23, result)
            assertTrue(output.contains("space & (test)"))
            assertTrue(output.contains("diagnostic"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pending cancellation prevents direct process creation`() {
        val executor = CmdExecutor(Mockito.mock(Logger::class.java))
        executor.prepareForInvoke()
        executor.release()
        assertEquals(IGradleCompileClient.Error.ERROR_CANCELED,
            executor.invokeProcess(listOf("missing-executable-must-not-start")))
    }

    @Test
    fun `cancellation terminates native process and its child`() {
        val root = Files.createTempDirectory("jugg native cancellation").toFile()
        val childPid = java.util.concurrent.atomic.AtomicLong()
        val ready = java.util.concurrent.CountDownLatch(1)
        val executor = CmdExecutor(Mockito.mock(Logger::class.java), object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (line.startsWith("CHILD=")) {
                    childPid.set(line.substringAfter('=').toLong())
                    ready.countDown()
                }
            }
            override fun onOutputErr(line: String) = Unit
        })
        val source = File(root, "Probe.java").apply { writeText("""
            class Probe {
                public static void main(String[] args) throws Exception {
                    if (args.length == 2) {
                        Process child = new ProcessBuilder(args[0], args[1], "child").start();
                        System.out.println("CHILD=" + child.pid());
                    }
                    Thread.sleep(30000);
                }
            }
        """.trimIndent()) }
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").path
        val result = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val worker = kotlin.concurrent.thread {
            result.set(executor.invokeProcess(listOf(javaExecutable, source.path, javaExecutable, source.path)))
        }
        try {
            assertTrue("Native child did not start", ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            executor.release()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(IGradleCompileClient.Error.ERROR_CANCELED, result.get())
            val child = ProcessHandle.of(childPid.get())
            if (child.isPresent) child.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(child.map { it.isAlive }.orElse(false))
        } finally {
            executor.release()
            worker.join(5000)
            ProcessHandle.of(childPid.get()).ifPresent { if (it.isAlive) it.destroyForcibly() }
            root.deleteRecursively()
        }
    }
}
