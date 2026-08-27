package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.ui.TooManyChangesConfirmResult
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.RecompileFiles
import com.sickworm.intellij.jugg.deploy.data.EffectedClassNode
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies await-const-ref timing in incremental compile flow.
 */
class IncrementalCompilerHelperTest {

    @Test
    fun `should expose compile error details for visible logs`() {
        val tempDir = Files.createTempDirectory("compile_error_summary").toFile()
        val compileFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = File(tempDir, "src/MainActivity.kt"),
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val compileResult = CompileResult(
            task = CompileTask(listOf(compileFile), File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
            details = listOf(
                Result.failure(
                    CompileError(compileFile, listOf(-1L to "java.lang.IllegalArgumentException: 25.0.3"))
                )
            ),
            outputs = emptyList(),
        )

        assertEquals(
            "MainActivity.kt: java.lang.IllegalArgumentException: 25.0.3",
            compileResult.toVisibleErrorMessage(),
        )
    }

    @Test
    fun `should hide quick failed entries from visible logs`() {
        val tempDir = Files.createTempDirectory("compile_error_summary").toFile()
        val sourceFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = File(tempDir, "src/MainActivity.kt"),
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val manifestFile = CompileFile(
            type = CompileFile.Type.AndroidManifest,
            file = File(tempDir, "AndroidManifest.xml"),
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val task = CompileTask(
            files = listOf(sourceFile, manifestFile),
            outputDir = File(tempDir, "task_out"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )
        val compileResult = CompileResult(
            task = task,
            details = listOf(
                Result.failure(
                    CompileError(manifestFile, listOf(0L to "aapt2 link failed"))
                )
            ),
            outputs = emptyList(),
        ).quickFailedOthers(task)

        assertEquals(
            "AndroidManifest.xml: aapt2 link failed",
            compileResult.toVisibleErrorMessage(),
        )
    }

    @Test
    fun `should return empty visible logs when only quick failed entries exist`() {
        val tempDir = Files.createTempDirectory("compile_error_summary").toFile()
        val sourceFile = CompileFile(
            type = CompileFile.Type.Kotlin,
            file = File(tempDir, "src/MainActivity.kt"),
            baseDir = tempDir,
            module = ModuleInfo.virtualModule,
        )
        val task = CompileTask(
            files = listOf(sourceFile),
            outputDir = File(tempDir, "task_out"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )
        val compileResult = CompileResult.empty(task).quickFailedOthers(task)

        assertEquals("", compileResult.toVisibleErrorMessage())
    }

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
        inOrder.verify(deployFileManager, Mockito.atLeastOnce()).awaitConstRefAnalysis(listOf(sourceFile.absolutePath))
        inOrder.verify(deployFileManager).getRecompileFiles(false, false, null)
    }

    @Test
    fun `should skip recompile detection when first round compile failed`() {
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
        assertEquals(compileResult, result.incrementalCompileResult)
        assertEquals("A.kt:1: compile failed", result.incrementalCompileResult?.toVisibleErrorMessage())
        // compile failure must not enter success branch: getRecompileFiles must not be called
        verify(deployFileManager, never()).getRecompileFiles(any(), any(), any())
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

    @Test
    fun `should skip effected files compiled in immediate previous round only`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_last_round_filter").toFile()
        val callerFile = File(tempDir, "src/Caller.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nclass Caller\n")
        }
        val defFile = File(tempDir, "src/Def.kt").apply {
            writeText("package com.example\nclass Def\n")
        }
        val callerChanged = changedFile(callerFile, tempDir)
        val successResult = { files: List<CompileFile> ->
            CompileResult(
                task = CompileTask(files, File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
                details = files.map { Result.success(it) },
                outputs = emptyList(),
            )
        }

        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        val juggDeployData: JuggDeployData = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(compileContext.mappingFile).thenReturn(null)
        whenever(compileContext.isMinified).thenReturn(false)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenAnswer { invocation ->
            val task = invocation.getArgument<CompileTask>(0)
            successResult(task.files)
        }
        whenever(fileChangesHandler.filter(any())).thenAnswer { invocation ->
            val files = invocation.getArgument<List<File>>(0)
            files.map { file -> changedFile(file, tempDir) }
        }
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        doNothing().whenever(deployFileManager).updateUncompiledFiles(any(), any())
        doNothing().whenever(deployFileManager).addStagingFiles(any())
        doNothing().whenever(deployFileManager).awaitConstRefAnalysis(any())
        val getRecompileCallCount = AtomicInteger(0)
        whenever(deployFileManager.getRecompileFiles(any(), any(), isNull())).thenAnswer {
            if (getRecompileCallCount.getAndIncrement() == 0) {
                RecompileFiles(
                    effectedSourceFiles = listOf(callerFile, defFile),
                    redexClasses = emptyList(),
                    juggDeployData = juggDeployData,
                )
            } else {
                RecompileFiles(
                    effectedSourceFiles = emptyList(),
                    redexClasses = emptyList(),
                    juggDeployData = juggDeployData,
                )
            }
        }

        val helper = buildHelper(compiler, pathManager, deployStateManager, deployFileManager, fileChangesHandler, retryResolver)
        helper.compile(
            undeployedFiles = listOf(callerChanged),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        val taskCaptor = argumentCaptor<CompileTask>()
        verify(compiler, Mockito.times(2)).compile(taskCaptor.capture())
        val secondRoundPaths = taskCaptor.allValues[1].files.map { it.file.absolutePath }.toSet()
        assertEquals(setOf(defFile.absolutePath), secondRoundPaths)
    }

    @Test
    fun `should recompile earlier round source when later round dependency changes`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_earlier_round_recompile").toFile()
        val callerFile = File(tempDir, "src/Caller.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nclass Caller\n")
        }
        val defFile = File(tempDir, "src/Def.kt").apply {
            writeText("package com.example\nclass Def\n")
        }
        val callerChanged = changedFile(callerFile, tempDir)
        val successResult = { files: List<CompileFile> ->
            CompileResult(
                task = CompileTask(files, File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
                details = files.map { Result.success(it) },
                outputs = emptyList(),
            )
        }

        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        val juggDeployData: JuggDeployData = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(compileContext.mappingFile).thenReturn(null)
        whenever(compileContext.isMinified).thenReturn(false)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenAnswer { invocation ->
            val task = invocation.getArgument<CompileTask>(0)
            successResult(task.files)
        }
        whenever(fileChangesHandler.filter(any())).thenAnswer { invocation ->
            val files = invocation.getArgument<List<File>>(0)
            files.map { file -> changedFile(file, tempDir) }
        }
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        doNothing().whenever(deployFileManager).updateUncompiledFiles(any(), any())
        doNothing().whenever(deployFileManager).addStagingFiles(any())
        doNothing().whenever(deployFileManager).awaitConstRefAnalysis(any())
        val getRecompileCallCount = AtomicInteger(0)
        whenever(deployFileManager.getRecompileFiles(any(), any(), isNull())).thenAnswer {
            when (getRecompileCallCount.getAndIncrement()) {
                0 -> RecompileFiles(
                    effectedSourceFiles = listOf(defFile),
                    redexClasses = emptyList(),
                    juggDeployData = juggDeployData,
                )
                1 -> RecompileFiles(
                    effectedSourceFiles = listOf(callerFile),
                    redexClasses = emptyList(),
                    juggDeployData = juggDeployData,
                )
                else -> RecompileFiles(
                    effectedSourceFiles = emptyList(),
                    redexClasses = emptyList(),
                    juggDeployData = juggDeployData,
                )
            }
        }

        val helper = buildHelper(compiler, pathManager, deployStateManager, deployFileManager, fileChangesHandler, retryResolver)
        val result = helper.compile(
            undeployedFiles = listOf(callerChanged),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        assertTrue(result.isSuccess)
        val taskCaptor = argumentCaptor<CompileTask>()
        verify(compiler, Mockito.times(3)).compile(taskCaptor.capture())
        assertEquals(setOf(defFile.absolutePath), taskCaptor.allValues[1].files.map { it.file.absolutePath }.toSet())
        assertEquals(setOf(callerFile.absolutePath), taskCaptor.allValues[2].files.map { it.file.absolutePath }.toSet())
    }

    @Test
    fun `should consume pending trigger before filtering to stop alternating continue compile`() {
        val tempDir = Files.createTempDirectory("inc_compile_effect_trigger_consume").toFile()
        val safeModeFile = File(tempDir, "src/SafeMode.kt").apply {
            parentFile.mkdirs()
            writeText("object SafeMode\n")
        }
        val lastCrashFile = File(tempDir, "src/LastCrashHandler.kt").apply {
            writeText("class LastCrashHandler\n")
        }
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/tencent/ibg/crash/safemode/SafeMode;",
                    sourceFileName = "SafeMode.kt",
                    effectedByClasses = listOf("Lcom/tencent/ibg/crash/safemode/CrashDataSource;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
                EffectedClassNode(
                    className = "Lcom/tencent/ibg/crash/safemode/LastCrashHandler;",
                    sourceFileName = "LastCrashHandler.kt",
                    effectedByClasses = listOf("Lcom/tencent/ibg/crash/safemode/CrashDataSource;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val safeModeKey = ContinueCompileEffectFilter.resolveEffectTriggerKey(changedFile(safeModeFile, tempDir), deployData)
        val lastCrashKey = ContinueCompileEffectFilter.resolveEffectTriggerKey(changedFile(lastCrashFile, tempDir), deployData)
        val satisfied = mutableSetOf(safeModeKey)
        val pending = mutableMapOf(lastCrashFile.absolutePath to lastCrashKey)
        val filtered = ContinueCompileEffectFilter.resolveUncompiledEffectedFiles(
            justCompiledFiles = listOf(changedFile(lastCrashFile, tempDir)),
            changedFiles = listOf(changedFile(safeModeFile, tempDir), changedFile(lastCrashFile, tempDir)),
            lastRoundCompiledPaths = setOf(lastCrashFile.absolutePath),
            satisfiedEffectTriggers = satisfied,
            pendingEffectTriggerKeys = pending,
            juggDeployData = deployData,
        )
        assertTrue(filtered.isEmpty())
        assertTrue(satisfied.contains(lastCrashKey))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `should not continue compile again when recompile keeps returning same effected files`() {
        val tempDir = Files.createTempDirectory("inc_compile_effect_trigger_no_loop").toFile()
        val triggerFile = File(tempDir, "src/CrashDataSource.kt").apply {
            parentFile.mkdirs()
            writeText("class CrashDataSource\n")
        }
        val safeModeFile = File(tempDir, "src/SafeMode.kt").apply {
            writeText("object SafeMode\n")
        }
        val lastCrashFile = File(tempDir, "src/LastCrashHandler.kt").apply {
            writeText("class LastCrashHandler\n")
        }
        val triggerChanged = changedFile(triggerFile, tempDir)
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/tencent/ibg/crash/safemode/SafeMode;",
                    sourceFileName = "SafeMode.kt",
                    effectedByClasses = listOf("Lcom/tencent/ibg/crash/safemode/CrashDataSource;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
                EffectedClassNode(
                    className = "Lcom/tencent/ibg/crash/safemode/LastCrashHandler;",
                    sourceFileName = "LastCrashHandler.kt",
                    effectedByClasses = listOf("Lcom/tencent/ibg/crash/safemode/CrashDataSource;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val successResult = { files: List<CompileFile> ->
            CompileResult(
                task = CompileTask(files, File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
                details = files.map { Result.success(it) },
                outputs = emptyList(),
            )
        }
        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(compileContext.mappingFile).thenReturn(null)
        whenever(compileContext.isMinified).thenReturn(false)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenAnswer { invocation ->
            successResult(invocation.getArgument<CompileTask>(0).files)
        }
        whenever(fileChangesHandler.filter(any())).thenAnswer { invocation ->
            invocation.getArgument<List<File>>(0).map { file -> changedFile(file, tempDir) }
        }
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        doNothing().whenever(deployFileManager).updateUncompiledFiles(any(), any())
        doNothing().whenever(deployFileManager).addStagingFiles(any())
        doNothing().whenever(deployFileManager).awaitConstRefAnalysis(any())
        whenever(deployFileManager.getRecompileFiles(any(), any(), isNull())).thenReturn(
            RecompileFiles(
                effectedSourceFiles = listOf(safeModeFile, lastCrashFile),
                redexClasses = emptyList(),
                juggDeployData = deployData,
            ),
        )
        val helper = buildHelper(compiler, pathManager, deployStateManager, deployFileManager, fileChangesHandler, retryResolver)
        val result = helper.compile(
            undeployedFiles = listOf(triggerChanged),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )
        assertTrue(result.isSuccess)
        verify(compiler, Mockito.times(2)).compile(any())
    }

    @Test
    fun `should continue compile last round source when getRecompileFiles marks top level facade effect`() {
        val tempDir = Files.createTempDirectory("inc_compile_helper_top_level_facade").toFile()
        val callerFile = File(tempDir, "src/Caller.kt").apply {
            parentFile.mkdirs()
            writeText("class Caller\n")
        }
        val callerChanged = changedFile(callerFile, tempDir)
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/example/Caller;",
                    sourceFileName = "Caller.kt",
                    effectedByClasses = listOf("Lcom/example/TopLevelClassKt;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val successResult = { files: List<CompileFile> ->
            CompileResult(
                task = CompileTask(files, File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
                details = files.map { Result.success(it) },
                outputs = emptyList(),
            )
        }
        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(compileContext.mappingFile).thenReturn(null)
        whenever(compileContext.isMinified).thenReturn(false)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenAnswer { invocation ->
            successResult(invocation.getArgument<CompileTask>(0).files)
        }
        whenever(fileChangesHandler.filter(any())).thenAnswer { invocation ->
            invocation.getArgument<List<File>>(0).map { file -> changedFile(file, tempDir) }
        }
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        doNothing().whenever(deployFileManager).updateUncompiledFiles(any(), any())
        doNothing().whenever(deployFileManager).addStagingFiles(any())
        doNothing().whenever(deployFileManager).awaitConstRefAnalysis(any())
        whenever(deployFileManager.getRecompileFiles(any(), any(), isNull())).thenReturn(
            RecompileFiles(
                effectedSourceFiles = listOf(callerFile),
                redexClasses = emptyList(),
                juggDeployData = deployData,
                topLevelFacadeEffectedSourcePaths = setOf(callerFile.absolutePath),
            ),
        )
        val helper = buildHelper(compiler, pathManager, deployStateManager, deployFileManager, fileChangesHandler, retryResolver)
        val result = helper.compile(
            undeployedFiles = listOf(callerChanged),
            uiHandler = CompileUiHandler.DEFAULT,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        assertTrue(result.isSuccess)
        verify(compiler, Mockito.times(2)).compile(any())
    }

    @Test
    fun `should stop continue compile when effect trigger already satisfied`() {
        val tempDir = Files.createTempDirectory("inc_compile_effect_trigger_satisfied").toFile()
        val safeModeFile = File(tempDir, "src/SafeMode.kt").apply {
            parentFile.mkdirs()
            writeText("object SafeMode\n")
        }
        val lastCrashFile = File(tempDir, "src/LastCrashHandler.kt").apply {
            writeText("class LastCrashHandler\n")
        }
        val triggerClasses = listOf("Lcom/tencent/ibg/crash/safemode/CrashDataSource;")
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/tencent/ibg/crash/safemode/SafeMode;",
                    sourceFileName = "SafeMode.kt",
                    effectedByClasses = triggerClasses,
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
                EffectedClassNode(
                    className = "Lcom/tencent/ibg/crash/safemode/LastCrashHandler;",
                    sourceFileName = "LastCrashHandler.kt",
                    effectedByClasses = triggerClasses,
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val satisfied = setOf(
            ContinueCompileEffectFilter.resolveEffectTriggerKey(changedFile(safeModeFile, tempDir), deployData),
            ContinueCompileEffectFilter.resolveEffectTriggerKey(changedFile(lastCrashFile, tempDir), deployData),
        )
        val filtered = ContinueCompileEffectFilter.filterUncompiledEffectedFiles(
            changedFiles = listOf(changedFile(safeModeFile, tempDir), changedFile(lastCrashFile, tempDir)),
            lastRoundCompiledPaths = setOf(safeModeFile.absolutePath),
            satisfiedEffectTriggers = satisfied,
            juggDeployData = deployData,
        )
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `should recompile last round source when effected by top level facade`() {
        val tempDir = Files.createTempDirectory("inc_compile_top_level_facade").toFile()
        val callerFile = File(tempDir, "src/Caller.kt").apply {
            parentFile.mkdirs()
            writeText("class Caller\n")
        }
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/example/Caller;",
                    sourceFileName = "Caller.kt",
                    effectedByClasses = listOf("Lcom/example/TopLevelClassKt;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val filtered = ContinueCompileEffectFilter.filterUncompiledEffectedFiles(
            changedFiles = listOf(changedFile(callerFile, tempDir)),
            lastRoundCompiledPaths = setOf(callerFile.absolutePath),
            satisfiedEffectTriggers = emptySet(),
            topLevelFacadeEffectedSourcePaths = setOf(callerFile.absolutePath),
            juggDeployData = deployData,
        )
        assertEquals(listOf(callerFile.absolutePath), filtered.map { it.file.absolutePath })
    }

    @Test
    fun `should recompile caller when new trigger from dependency was not satisfied before`() {
        val tempDir = Files.createTempDirectory("inc_compile_effect_trigger_new").toFile()
        val callerFile = File(tempDir, "src/Caller.kt").apply {
            parentFile.mkdirs()
            writeText("class Caller\n")
        }
        val defFile = File(tempDir, "src/Def.kt").apply {
            writeText("class Def\n")
        }
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/example/Caller;",
                    sourceFileName = "Caller.kt",
                    effectedByClasses = listOf("Lcom/example/Def;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val satisfied = setOf(
            ContinueCompileEffectFilter.resolveEffectTriggerKey(changedFile(defFile, tempDir), deployDataWithSourceEffects(
                listOf(
                    EffectedClassNode(
                        className = "Lcom/example/Def;",
                        sourceFileName = "Def.kt",
                        effectedByClasses = listOf("Lcom/example/Caller;"),
                        effectedType = EffectedClassNode.EffectedType.SOURCE,
                    ),
                ),
            )),
        )
        val filtered = ContinueCompileEffectFilter.filterUncompiledEffectedFiles(
            changedFiles = listOf(changedFile(callerFile, tempDir)),
            lastRoundCompiledPaths = setOf(defFile.absolutePath),
            satisfiedEffectTriggers = satisfied,
            juggDeployData = deployData,
        )
        assertEquals(listOf(callerFile.absolutePath), filtered.map { it.file.absolutePath })
    }

    @Test
    fun `should fallback when continue compile exceeds too many changes`() {
        val fixture = continueCompileTooManyFixture()
        val result = withLoweredSourceFilePointLimit(2) {
            fixture.helper.compile(
                undeployedFiles = listOf(fixture.triggerChanged),
                uiHandler = CompileUiHandler.DEFAULT,
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )
        }
        assertFalse(result.isSuccess)
        assertTrue(result.isCanFallback)
        assertEquals("Too many changes", result.failedReason)
        verify(fixture.compiler, Mockito.times(1)).compile(any())
    }

    @Test
    fun `should continue compile when user confirms too many changes`() {
        val fixture = continueCompileTooManyFixture()
        val uiHandler = mock<CompileUiHandler>()
        whenever(uiHandler.confirmTooManyChanges(any())).thenReturn(TooManyChangesConfirmResult.CONTINUE)
        whenever(uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        val result = withLoweredSourceFilePointLimit(2) {
            fixture.helper.compile(
                undeployedFiles = listOf(fixture.triggerChanged),
                uiHandler = uiHandler,
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )
        }
        assertTrue(result.isSuccess)
        verify(uiHandler).confirmTooManyChanges(any())
        verify(fixture.compiler, Mockito.times(2)).compile(any())
    }

    @Test
    fun `should cancel continue compile when user cancels too many changes`() {
        val fixture = continueCompileTooManyFixture()
        val uiHandler = mock<CompileUiHandler>()
        whenever(uiHandler.confirmTooManyChanges(any())).thenReturn(TooManyChangesConfirmResult.CANCEL)
        whenever(uiHandler.createCompileStatusHolder()).thenReturn(CompileStatusHolder.DEFAULT)
        val result = withLoweredSourceFilePointLimit(2) {
            fixture.helper.compile(
                undeployedFiles = listOf(fixture.triggerChanged),
                uiHandler = uiHandler,
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )
        }
        assertFalse(result.isSuccess)
        assertFalse(result.isCanFallback)
        assertEquals("Compile canceled", result.failedReason)
        verify(uiHandler).cancel()
        verify(fixture.compiler, Mockito.times(1)).compile(any())
    }

    private fun continueCompileTooManyFixture(): ContinueCompileTooManyFixture {
        val tempDir = Files.createTempDirectory("inc_compile_too_many_changes").toFile()
        val triggerFile = File(tempDir, "src/Trigger.kt").apply {
            parentFile.mkdirs()
            writeText("class Trigger\n")
        }
        val effectedFile = File(tempDir, "src/Effected.kt").apply {
            writeText("class Effected\n")
        }
        val triggerChanged = changedFile(triggerFile, tempDir)
        val deployData = deployDataWithSourceEffects(
            listOf(
                EffectedClassNode(
                    className = "Lcom/example/Effected;",
                    sourceFileName = "Effected.kt",
                    effectedByClasses = listOf("Lcom/example/Trigger;"),
                    effectedType = EffectedClassNode.EffectedType.SOURCE,
                ),
            ),
        )
        val successResult = { files: List<CompileFile> ->
            CompileResult(
                task = CompileTask(files, File(tempDir, "task_out"), CompileStatusHolder.DEFAULT),
                details = files.map { Result.success(it) },
                outputs = emptyList(),
            )
        }
        val compiler: JuggCompiler = mock()
        val compileContext: ICompileContext = mock()
        val pathManager: JuggPathManager = mock()
        val deployStateManager: IDeployStateManager = mock()
        val deployFileManager: DeployFileManager = mock()
        val fileChangesHandler: IFileChangesHandler = mock()
        val retryResolver: IIncrementalCompileRetryResolver = mock()
        whenever(compiler.context).thenReturn(compileContext)
        whenever(compileContext.mappingFile).thenReturn(null)
        whenever(compileContext.isMinified).thenReturn(false)
        whenever(pathManager.stagingDir).thenReturn(File(tempDir, "staging"))
        whenever(compiler.compile(any())).thenAnswer { invocation ->
            successResult(invocation.getArgument<CompileTask>(0).files)
        }
        whenever(fileChangesHandler.filter(any())).thenAnswer { invocation ->
            invocation.getArgument<List<File>>(0).map { file -> changedFile(file, tempDir) }
        }
        whenever(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        doNothing().whenever(deployFileManager).updateUncompiledFiles(any(), any())
        doNothing().whenever(deployFileManager).addStagingFiles(any())
        doNothing().whenever(deployFileManager).awaitConstRefAnalysis(any())
        whenever(deployFileManager.getRecompileFiles(any(), any(), isNull())).thenReturn(
            RecompileFiles(
                effectedSourceFiles = listOf(effectedFile),
                redexClasses = emptyList(),
                juggDeployData = deployData,
            ),
            RecompileFiles(
                effectedSourceFiles = emptyList(),
                redexClasses = emptyList(),
                juggDeployData = deployData,
            ),
        )
        return ContinueCompileTooManyFixture(
            helper = buildHelper(compiler, pathManager, deployStateManager, deployFileManager, fileChangesHandler, retryResolver),
            compiler = compiler,
            triggerChanged = triggerChanged,
        )
    }

    private data class ContinueCompileTooManyFixture(
        val helper: IncrementalCompilerHelper,
        val compiler: JuggCompiler,
        val triggerChanged: ChangedFile,
    )

    private fun <T> withLoweredSourceFilePointLimit(limit: Int, block: () -> T): T {
        val original = JuggSettings.maxCompileSourceFilePoints
        JuggSettings.maxCompileSourceFilePoints = limit
        try {
            return block()
        } finally {
            JuggSettings.maxCompileSourceFilePoints = original
        }
    }

    private fun deployDataWithSourceEffects(nodes: List<EffectedClassNode>): JuggDeployData {
        val deployData: JuggDeployData = mock()
        whenever(deployData.effectedClassNodes).thenReturn(nodes)
        whenever(deployData.constRefEffectedSourcePaths).thenReturn(emptyList())
        return deployData
    }

    private fun changedFile(file: File, baseDir: File) = ChangedFile(
        type = CompileFile.Type.Kotlin,
        file = file,
        baseDir = baseDir,
        module = ModuleInfo.virtualModule,
    )

    private fun buildHelper(
        compiler: JuggCompiler,
        pathManager: JuggPathManager,
        deployStateManager: IDeployStateManager,
        deployFileManager: DeployFileManager,
        fileChangesHandler: IFileChangesHandler,
        retryResolver: IIncrementalCompileRetryResolver,
    ) = IncrementalCompilerHelper(
        compiler = compiler,
        pathManager = pathManager,
        deployStateManager = deployStateManager,
        deployFileManager = deployFileManager,
        fileChangesHandler = fileChangesHandler,
        retryResolver = retryResolver,
        loggerArg = logger,
    )
}
