package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.ArgumentMatchers.anyString
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** L1 regression coverage for compiler-output framing, diagnostics and long KAPT lines. */
class KotlinCompilerOutputParserTest {
    private val logger = mock(Logger::class.java)
    private val source = CompileFile(
        CompileFile.Type.Kotlin, File("/project/Source.kt"), File("/project"), ModuleInfo.virtualModule,
    )

    @Test
    fun `silent parsing retains diagnostics and publishes them only on request`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger, isOutputEnabled = false)
        val message = "${source.file.path}:7:3: error: unresolved reference: Missing"
        parser.printStream.println(message)
        parser.flush()

        assertEquals(message, parser.getResult(false).single().getFailure().errors.single().second)
        verify(logger, never()).warn(anyString())
        verify(logger).debug(message)
        parser.printDiagnostics()
        verify(logger).warn(message)
    }

    @Test
    fun `silent exceptions retain the missing class cause for final reporting`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger, isOutputEnabled = false)
        parser.printStream.println("exception: org.jetbrains.kotlin.util.FileAnalysisException: analysis failed")
        parser.printStream.println("Caused by: java.io.FileNotFoundException: /baseline/Missing.class")
        parser.flush()

        assertTrue(parser.getResult(false).single().getFailure().errorMessages.contains("/baseline/Missing.class"))
        verify(logger, never()).warn(anyString())
        parser.printDiagnostics()
        verify(logger).warn("org.jetbrains.kotlin.util.FileAnalysisException: analysis failed\n" +
                "Caused by: java.io.FileNotFoundException: /baseline/Missing.class")
    }

    @Test
    fun `KAPT result collection stays silent and final diagnostics are deduplicated`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger,
            forceCompilerOutputDebug = true, isOutputEnabled = false)
        repeat(2) { parser.printStream.println("error: missing processor option") }
        parser.flush()

        repeat(2) { parser.getResult(false) }
        verify(logger, never()).warn(anyString())
        parser.printDiagnostics()
        verify(logger).warn("error: missing processor option")
    }

    @Test(timeout = 5_000)
    fun `long KAPT classpath output preserves following diagnostics promptly`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger, forceCompilerOutputDebug = true)
        parser.printStream.println("Compile classpath: " + "/dependencies/library.jar,".repeat(2_000))
        parser.printStream.println("error: original Kotlin diagnostic")
        parser.flush()

        assertEquals("error: original Kotlin diagnostic", parser.getResult(false).single().getFailure().errors.single().second)
    }

    @Test
    fun `prefixed source errors retain file and line`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger)
        parser.printStream.println("${source.file.path}:7:3: error: unresolved reference: Missing")
        parser.flush()

        val error = parser.getResult(false).single().getFailure()
        assertEquals(7L, error.errors.single().first)
        assertTrue(error.hasDirectSourceDiagnostic)
        assertEquals("${source.file.path}:7:3: error: unresolved reference: Missing", error.errors.single().second)
    }

    @Test
    fun `compiler exceptions retain their summaries`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger)
        parser.printStream.println("exception: java.lang.IllegalArgumentException: 25.0.3")
        parser.printStream.println("\tat org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:298)")
        parser.flush()

        assertEquals("java.lang.IllegalArgumentException: 25.0.3",
            parser.getResult(false).single().getFailure().errors.single().second)
    }

    @Test
    fun `output frames retain generated files`() {
        val root = Files.createTempDirectory("kotlin-output-parser").toFile()
        try {
            val input = File(root, "Source.kt").apply { writeText("class Source") }
            val output = File(root, "Source.class").apply { writeBytes(byteArrayOf()) }
            val parser = KotlinCompilerOutputParser(emptyList(), logger)
            parser.printStream.println("output: output:")
            parser.printStream.println(output.path)
            parser.printStream.println("Sources:")
            parser.printStream.println(input.path)
            parser.flush()

            assertEquals(listOf(output), parser.outputs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `lines containing multiple tags retain the last tag classification`() {
        val parser = KotlinCompilerOutputParser(listOf(source), logger)
        val message = "warning: nested diagnostic error: original Kotlin diagnostic"
        parser.printStream.println(message)
        parser.flush()

        verify(logger).warn(message)
        assertTrue(parser.getResult(false).single().isFailed)
    }
}
