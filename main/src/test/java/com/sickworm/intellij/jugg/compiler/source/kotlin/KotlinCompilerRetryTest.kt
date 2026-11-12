package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*
import java.io.File
import java.io.PrintStream
import kotlin.test.*

/** L2 regressions for final diagnostics and artifacts across compiler recovery. */
class KotlinCompilerRetryTest {
    @get:Rule val folder = TemporaryFolder()
    private val logger = mock<Logger>()
    private val original = "java.io.FileNotFoundException: /baseline/Missing.class"

    @Test fun `failed recovery returns and reports the original error`() {
        val result = compile(ExitCode.INTERNAL_ERROR, ExitCode.COMPILATION_ERROR)
        assertFalse(result.isAllSuccess)
        assertEquals(original, result.failedFiles.single().getFailure().errorMessages)
        assertTrue(result.outputs.isEmpty())
        verify(logger).warn(original)
        verify(logger, never()).warn("error: unresolved reference: RetryOnly")
    }

    @Test fun `successful recovery returns artifacts without intermediate diagnostics`() {
        val result = compile(ExitCode.INTERNAL_ERROR, ExitCode.OK)
        assertTrue(result.isAllSuccess)
        assertTrue(result.outputs.single().file.isFile)
        verify(logger, never()).warn(original)
    }

    @Test fun `ordinary source failure still reports its diagnostic`() {
        val result = compile(ExitCode.COMPILATION_ERROR, null)
        assertFalse(result.isAllSuccess)
        verify(logger).warn("error: unresolved reference: RetryOnly")
    }

    @Test fun `first attempt success returns artifacts`() {
        val result = compile(ExitCode.OK, null)
        assertTrue(result.isAllSuccess)
        assertTrue(result.outputs.single().file.isFile)
    }

    private fun compile(first: ExitCode, retry: ExitCode?): CompileResult {
        val root = folder.newFolder()
        val module = ModuleInfo.virtualModule.copy(
            name = "sample", moduleRootDir = root, projectRootDir = root, buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(root, root, "debug", buildDirRelativePath = ""),
        )
        val source = File(root, "Source.kt").apply { writeText("class Source") }
        val context = mock<ICompileContext>()
        whenever(context.projectDir).thenReturn(root)
        whenever(context.tempCompileDir).thenReturn(File(root, "tmp").apply { mkdirs() })
        whenever(context.modules).thenReturn(mapOf(module.name to module))
        whenever(context.getModuleDependencies(any(), any())).thenReturn(emptyList())
        val task = CompileTask(listOf(CompileFile(CompileFile.Type.Kotlin, source, root, module)),
            File(root, "output").apply { mkdirs() }, CompileStatusHolder.DEFAULT)
        var attempts = 0
        mockConstruction(K2JVMCompilerIsolate::class.java) { compiler, _ ->
            whenever(compiler.exec(any(), any())).thenAnswer { invocation ->
                val code = if (attempts++ == 0) first else requireNotNull(retry)
                writeOutput(invocation.getArgument(0), invocation.getArgument(1), source, code)
                code
            }
        }.use {
            val result = KotlinCompilerInvoker().compile(context, module, task, logger,
                KotlinCompilerInvoker.Options(forceUseEmbeddedKotlinCompiler = true, javaSourceDirs = emptyList()))
            assertEquals(if (retry == null) 1 else 2, attempts)
            return result
        }
    }

    private fun writeOutput(stream: PrintStream, args: Array<String>, source: File, code: ExitCode) {
        when (code) {
            ExitCode.INTERNAL_ERROR -> stream.println("exception: $original")
            ExitCode.OK -> {
                val output = File(args[args.indexOf("-d") + 1], "Source.class")
                output.parentFile.mkdirs()
                output.writeBytes(byteArrayOf(1))
                stream.println("output: output:\n${output.path}\nSources:\n${source.path}")
            }
            else -> stream.println("error: unresolved reference: RetryOnly")
        }
    }
}
