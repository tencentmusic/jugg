package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.RecompileFiles
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies await-const-ref timing in incremental compile flow.
 */
class IncrementalCompilerHelperTest {

    @Test
    fun `should await const ref after compile success and before recompile detection on first round`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_success").toFile()
        val sourceFile = File(tempDir, "src/A.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val A = 1\n")
        }
        val changedFile = ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileResult = CompileResult(
            task = CompileTask(listOf(compileFile), File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
            details = listOf(Result.success(compileFile)),
            outputs = emptyList(),
        )

        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val dependencyMissingResolver: IIncrementalCompileRetryResolver = mock()
        val juggDeployData: JuggDeployData = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenReturn(compileResult)
        whenever(fileChangesHandler.filter(any())).thenReturn(emptyList())
        whenever(
            deployFileManager.getRecompileFiles(false, false, null)
        ).thenReturn(
            RecompileFiles(
                effectedSourceFiles = emptyList(),
                redexClasses = emptyList(),
                juggDeployData = juggDeployData,
            )
        )

        val helper = IncrementalCompilerHelper(
            compiler = compiler,
            pathManager = pathManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            fileChangesHandler = fileChangesHandler,
            retryResolver = dependencyMissingResolver,
            loggerArg = logger,
        )

        val result = helper.compile(
            undeployedFiles = listOf(changedFile),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )
        assertTrue(result.isSuccess)

        val inOrder = Mockito.inOrder(compiler, deployFileManager)
        inOrder.verify(compiler).compile(any())
        inOrder.verify(deployFileManager).awaitConstRefAnalysis(listOf(sourceFile.absolutePath))
        inOrder.verify(deployFileManager).getRecompileFiles(false, false, null)
    }

    @Test
    fun `should skip const ref await when first round compile failed`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_failed").toFile()
        val sourceFile = File(tempDir, "src/A.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val A = 1\n")
        }
        val changedFile = ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileResult = CompileResult(
            task = CompileTask(listOf(compileFile), File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
            details = listOf(Result.failure(CompileError(compileFile, listOf(1L to "compile failed")))),
            outputs = emptyList(),
        )

        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val dependencyMissingResolver: IIncrementalCompileRetryResolver = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenReturn(compileResult)
        whenever(dependencyMissingResolver.resolve(any())).thenReturn(false)

        val helper = IncrementalCompilerHelper(
            compiler = compiler,
            pathManager = pathManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            fileChangesHandler = fileChangesHandler,
            retryResolver = dependencyMissingResolver,
            loggerArg = logger,
        )

        val result = helper.compile(
            undeployedFiles = listOf(changedFile),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )
        assertFalse(result.isSuccess)
        verify(deployFileManager, never()).awaitConstRefAnalysis(listOf(sourceFile.absolutePath))
    }

    @Test
    fun `should retry compile once and succeed when retryResolver returns true`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_retry_success").toFile()
        val sourceFile = File(tempDir, "src/A.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val A = 1\n")
        }
        val changedFile = ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val failResult = CompileResult(
            task = CompileTask(listOf(compileFile), File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
            details = listOf(Result.failure(CompileError(compileFile, listOf(1L to "compiler.err.cant.resolve.location foo")))),
            outputs = emptyList(),
        )
        val successResult = CompileResult(
            task = CompileTask(listOf(compileFile), File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
            details = listOf(Result.success(compileFile)),
            outputs = emptyList(),
        )

        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        val juggDeployData: JuggDeployData = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        // First compile fails, second (retry) succeeds
        whenever(compiler.compile(any())).thenReturn(failResult, successResult)
        whenever(retryResolver.resolve(any())).thenReturn(true)
        whenever(fileChangesHandler.filter(any())).thenReturn(emptyList())
        whenever(
            deployFileManager.getRecompileFiles(false, false, null)
        ).thenReturn(
            RecompileFiles(
                effectedSourceFiles = emptyList(),
                redexClasses = emptyList(),
                juggDeployData = juggDeployData,
            )
        )

        val helper = IncrementalCompilerHelper(
            compiler = compiler,
            pathManager = pathManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            fileChangesHandler = fileChangesHandler,
            retryResolver = retryResolver,
            loggerArg = logger,
        )

        val result = helper.compile(
            undeployedFiles = listOf(changedFile),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        assertTrue(result.isSuccess)
        // retryResolver.resolve() called once for the first failure; not called on retry round
        verify(retryResolver, Mockito.times(1)).resolve(any())
        // compiler.compile() called twice: first round + retry round
        verify(compiler, Mockito.times(2)).compile(any())
    }

    @Test
    fun `should not retry again when retry compile also fails`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_retry_failed").toFile()
        val sourceFile = File(tempDir, "src/A.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val A = 1\n")
        }
        val changedFile = ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = sourceFile,
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val failResult = CompileResult(
            task = CompileTask(listOf(compileFile), File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
            details = listOf(Result.failure(CompileError(compileFile, listOf(1L to "compiler.err.cant.resolve.location foo")))),
            outputs = emptyList(),
        )

        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        // Both first and retry compile fail
        whenever(compiler.compile(any())).thenReturn(failResult)
        whenever(retryResolver.resolve(any())).thenReturn(true)

        val helper = IncrementalCompilerHelper(
            compiler = compiler,
            pathManager = pathManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            fileChangesHandler = fileChangesHandler,
            retryResolver = retryResolver,
            loggerArg = logger,
        )

        val result = helper.compile(
            undeployedFiles = listOf(changedFile),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        assertFalse(result.isSuccess)
        // retryResolver.resolve() called only once: retry round uses isRetry=true, skips resolver
        verify(retryResolver, Mockito.times(1)).resolve(any())
        // compiler.compile() called twice: first round + retry round
        verify(compiler, Mockito.times(2)).compile(any())
    }
}
