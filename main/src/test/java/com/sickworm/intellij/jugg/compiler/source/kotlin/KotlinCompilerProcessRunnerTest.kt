package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.isWindows
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.jetbrains.kotlin.cli.common.arguments.readArgumentsFromArgFile
import org.jetbrains.kotlin.cli.common.ExitCode
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinCompilerProcessRunnerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val javaHome = File(System.getProperty("java.home"))
    private val messages = CopyOnWriteArrayList<String>()
    private val logger = mock<Logger>().apply {
        doAnswer { messages += it.getArgument<String>(0); null }.whenever(this).debug(any<String>())
    }

    @Test(timeout = 20000)
    fun `diagnostics capture the blocked compiler thread without stopping compilation`() {
        val process = startWaitingCompiler()
        try {
            diagnostics(process, javaHome).captureCompilerThreads()

            val output = messages.joinToString("\n")
            assertTrue(output.contains("compiler-fixture-blocked"), output)
            assertTrue(output.contains("java.util.concurrent.CountDownLatch.await"), output)
            assertTrue(process.isAlive)
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 20000)
    fun `missing attach tool preserves compilation and records unavailable diagnostics`() {
        val process = startWaitingCompiler()
        try {
            diagnostics(process, temporaryFolder.newFolder("no-jcmd")).captureCompilerThreads()

            assertTrue(messages.any { it.contains("unavailable") && it.contains(process.pid().toString()) })
            assertTrue(process.isAlive)
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 20000)
    fun `stalled attach tool has a bounded wait and retains partial diagnostics`() {
        assumeFalse(isWindows)
        val toolHome = temporaryFolder.newFolder("stalled-jcmd")
        File(toolHome, "bin/jcmd").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nprintf 'partial-dump-before-hang\\n'\nexec sleep 30\n")
            assertTrue(setExecutable(true))
        }
        val process = startWaitingCompiler()
        try {
            val start = System.nanoTime()
            diagnostics(process, toolHome).captureCompilerThreads()

            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 12)
            assertTrue(messages.any { it.contains("partial-dump-before-hang") })
            assertTrue(messages.any { it.contains("timed out") })
            assertTrue(process.isAlive)
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 20000)
    fun `diagnostics preserve successful compiler output and exit status`() {
        val classes = compileFixture()
        val output = ByteArrayOutputStream()
        val result = KotlinCompilerProcessRunner(logger).exec(
            compileEnv = listOf("JAVA_HOME=${javaHome.path}"),
            projectDir = temporaryFolder.root,
            task = CompileTask(emptyList(), temporaryFolder.root, CompileStatusHolder.DEFAULT),
            compilerClasspath = listOf(classes),
            compilerArgs = listOf("finish"),
            outputStream = PrintStream(output, true, "UTF-8"),
            ideaHomePath = null,
        )

        assertEquals(ExitCode.OK, result)
        assertEquals("fixture ready 中文", output.toString("UTF-8").trim())
    }

    @Test(timeout = 25000)
    fun `stalled output cleanup records both waiting host threads`() {
        val classes = compileFixture()
        val outputEntered = CountDownLatch(1)
        val releaseOutput = CountDownLatch(1)
        val capturedThreads = CountDownLatch(1)
        doAnswer {
            val message = it.getArgument<String>(0)
            messages += message
            if (message.contains("host thread=jugg-kotlin-compiler-output")) capturedThreads.countDown()
            null
        }.whenever(logger).debug(any<String>())
        val output = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                outputEntered.countDown()
                releaseOutput.await()
            }
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ExitCode> {
                KotlinCompilerProcessRunner(logger).exec(
                    listOf("JAVA_HOME=${javaHome.path}"), temporaryFolder.root,
                    CompileTask(emptyList(), temporaryFolder.root, CompileStatusHolder.DEFAULT),
                    listOf(classes), listOf("finish"), output, null,
                )
            }
            assertTrue(outputEntered.await(5, TimeUnit.SECONDS))
            assertTrue(capturedThreads.await(15, TimeUnit.SECONDS))
            val evidence = messages.joinToString("\n")
            assertTrue(evidence.contains("java.lang.Thread.join"), evidence)
            assertTrue(evidence.contains("java.util.concurrent.CountDownLatch.await"), evidence)
            releaseOutput.countDown()
            assertEquals(ExitCode.OK, result.get(5, TimeUnit.SECONDS))
        } finally {
            releaseOutput.countDown()
            executor.shutdownNow()
        }
    }

    private fun diagnostics(process: Process, toolHome: File) = KotlinCompilerProcessDiagnostics(
        process, toolHome, Thread.currentThread(), Thread.currentThread(), logger,
    )

    private fun startWaitingCompiler(): Process {
        val classes = compileFixture()
        val process = ProcessBuilder(
            File(javaHome, if (isWindows) "bin/java.exe" else "bin/java").path,
            "-Dfile.encoding=UTF-8", "-cp", classes.path, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "wait",
        ).redirectErrorStream(true).start()
        assertEquals("fixture ready 中文", process.inputStream.bufferedReader(Charsets.UTF_8).readLine())
        return process
    }

    private fun compileFixture(): File {
        val classes = temporaryFolder.newFolder()
        val source = File(classes, "K2JVMCompiler.java").apply {
            writeText("""
                package org.jetbrains.kotlin.cli.jvm;
                public class K2JVMCompiler {
                    public static void main(String[] args) throws Exception {
                        String mode = args[0].startsWith("@")
                            ? java.nio.file.Files.readString(java.nio.file.Path.of(args[0].substring(1))) : args[0];
                        Thread.currentThread().setName("compiler-fixture-blocked");
                        System.out.println("fixture ready 中文");
                        System.out.flush();
                        if (mode.contains("wait")) new java.util.concurrent.CountDownLatch(1).await();
                    }
                }
            """.trimIndent(), Charsets.UTF_8)
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-encoding", "UTF-8", "-d", classes.path, source.path,
        ))
        return classes
    }

    @Test
    fun `uses Gradle java home and opens javac modules for isolated compiler`() {
        val javaHome = File("/tmp/gradle-jdk")
        val compilerArgsFile = File("/tmp/jugg-kotlinc.args")

        val command = KotlinCompilerProcessRunner.buildCommand(
            javaHome = javaHome,
            javaFeature = 17,
            compilerClasspath = listOf(File("/tmp/kotlin-compiler.jar")),
            compilerArgsFile = compilerArgsFile,
        )

        assertEquals(File(javaHome, if (isWindows) "bin/java.exe" else "bin/java").path, command.first())
        assertTrue(command.contains("--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"))
        assertTrue(command.contains("--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"))
        assertTrue(command.contains("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"))
        assertEquals("@${compilerArgsFile.absolutePath}", command.last())
    }

    @Test
    fun `does not add module flags for java 8`() {
        val command = KotlinCompilerProcessRunner.buildCommand(
            javaHome = File("/tmp/jdk8"),
            javaFeature = 8,
            compilerClasspath = listOf(File("/tmp/kotlin-compiler.jar")),
            compilerArgsFile = File("/tmp/jugg-kotlinc.args"),
        )

        assertFalse(command.any { it.startsWith("--add-exports") })
        assertFalse(command.any { it.startsWith("--add-opens") })
    }

    @Test
    fun `passes validated IDE home as one JVM argument before compiler main`() {
        val home = File("/tmp/Android Studio")
        val command = KotlinCompilerProcessRunner.buildCommand(
            javaHome = File("/tmp/jdk"),
            javaFeature = 17,
            compilerClasspath = listOf(File("/tmp/kotlin-compiler.jar")),
            compilerArgsFile = File("/tmp/jugg-kotlinc.args"),
            ideaHomePath = home.path,
        )

        val option = "-Didea.home.path=${home.path}"
        assertEquals(1, command.count { it == option })
        assertTrue(command.indexOf(option) < command.indexOf("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"))
    }

    @Test
    fun `omits IDE home argument when no valid home is available`() {
        val command = KotlinCompilerProcessRunner.buildCommand(
            javaHome = File("/tmp/jdk"),
            javaFeature = 17,
            compilerClasspath = listOf(File("/tmp/kotlin-compiler.jar")),
            compilerArgsFile = File("/tmp/jugg-kotlinc.args"),
            ideaHomePath = null,
        )

        assertFalse(command.any { it.startsWith("-Didea.home.path=") })
    }

    @Test
    fun `prefers java home from compile environment`() {
        val javaHome = KotlinCompilerProcessRunner.resolveJavaHome(
            compileEnv = listOf("PATH=/usr/bin", "JAVA_HOME=/tmp/gradle-jdk"),
            systemJavaHome = "/tmp/ide-jbr",
        )

        assertEquals(File("/tmp/gradle-jdk"), javaHome)
    }

    @Test
    fun `renders Kotlin argfile arguments without losing spaces quotes or backslashes`() {
        val arguments = listOf(
            "-cp",
            "C:\\repo with space\\libs\\a.jar;C:\\libs\\b.jar",
            "plugin:sample=value\"quoted\"",
            "",
        )

        val rendered = KotlinCompilerProcessRunner.renderCompilerArgs(arguments)

        assertContentEquals(arguments, readArgumentsFromArgFile(rendered))
    }
}
