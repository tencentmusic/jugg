package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.JsonParser
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.After
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import javax.swing.SwingUtilities

class AskAiInvestigationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() = TestGlobal.init()
    }

    @get:Rule val temporaryFolder = TemporaryFolder()
    private val investigationProjects = mutableListOf<Project>()

    @After
    fun removeInvestigationLoggers() {
        investigationProjects.forEach { JuggLogger.unregister(it) }
    }

    private fun investigationProject(): Project {
        val directory = temporaryFolder.newFolder()
        val project = mock<Project>()
        whenever(project.basePath).thenReturn(directory.absolutePath)
        JuggLogger.register(project, File(directory, "logs"))
        investigationProjects += project
        return project
    }

    @Test
    fun `local rsync setup failure publishes one setup link without collecting investigation data`() {
        val delegate = mock<CompileUiHandler>()
        whenever(delegate.createOutputParser()).thenReturn(com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient.TerminalOutputListener.IDLE)
        val console = mock<ConsoleView>()
        Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }
            .`when`(console).performWhenNoDeferredOutput(Mockito.any(Runnable::class.java))
        val investigation = mock<AskAiInvestigation>()
        val handler = AskAiCompileUiHandler(delegate, investigationProject(), console, mock(), investigation, mock(), 123L)
        val output = handler.createOutputParser()
        val failure = com.sickworm.intellij.jugg.gradle.compile.WindowsRsyncSetupException("rsync_not_found")
        output.onWindowsRsyncSetupFailure(failure)
        output.onWindowsRsyncSetupFailure(failure)
        handler.onEnd(RunResult.FAILED)
        SwingUtilities.invokeAndWait { }
        Mockito.verify(console, Mockito.times(1)).printHyperlink(Mockito.contains("Windows rsync"), Mockito.any())
        Mockito.verifyNoInteractions(investigation)
        Mockito.verify(delegate).onEnd(RunResult.FAILED)
    }

    @Test
    fun `project disposal during investigation does not prevent the original completion callback`() {
        val project = investigationProject()
        val delegate = mock<CompileUiHandler>()
        val handler = AskAiCompileUiHandler(delegate, project, mock(), mock(), mock(), mock(), 123L)
        JuggLogger.unregister(project)

        handler.onEnd(RunResult.FAILED)

        Mockito.verify(delegate, Mockito.times(1)).onEnd(RunResult.FAILED)
    }

    @Test
    fun `incremental failure keeps task message summary without duplicating file log exception stacks`() {
        val project = investigationProject()
        val investigation = mock<AskAiInvestigation>()
        val options = mock<JuggGradleCompileOptions>()
        val handler = AskAiCompileUiHandler(mock(), project, mock(), options, investigation, mock(), 123L)
        var captured = ""
        whenever(investigation.preserveCompileFailure(org.mockito.kotlin.eq(options), org.mockito.kotlin.eq(RunResult.FAILED),
            org.mockito.kotlin.eq(123L), Mockito.anyString())).thenAnswer {
            captured = it.getArgument(3)
            null
        }

        JuggLogger.getInstance(project, "Compiler").warn("Final compile exception", IllegalStateException("missing symbol"))
        handler.onEnd(RunResult.FAILED)

        assertTrue(captured.contains("Final compile exception"))
        assertFalse(captured.contains("IllegalStateException: missing symbol"))
    }

    @Test
    fun `compile failure publishes saved investigation after preserving evidence and keeps completion`() {
        val delegate = mock<CompileUiHandler>()
        val console = mock<ConsoleView>()
        val investigation = mock<AskAiInvestigation>()
        val options = mock<JuggGradleCompileOptions>()
        val snapshot = InvestigationSnapshotBuilder.Snapshot(File("/saved/failure"), "saved prompt", 0)
        val failed = RunResult(false, false, false, false, errorLog = listOf("compiler error"))
        whenever(investigation.preserveCompileFailure(options, failed, 123L, "compiler error")).thenReturn(snapshot)
        Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }
            .`when`(console).performWhenNoDeferredOutput(Mockito.any(Runnable::class.java))

        AskAiCompileUiHandler(delegate, investigationProject(), console, options, investigation, mock(), 123L).onEnd(failed)
        SwingUtilities.invokeAndWait { }

        Mockito.verify(console).printHyperlink(Mockito.contains("Ask AI"), Mockito.any())
        val order = Mockito.inOrder(investigation, delegate)
        order.verify(investigation).preserveCompileFailure(options, failed, 123L, "compiler error")
        order.verify(delegate).onEnd(failed)
    }

    @Test
    fun `success cancellation deploy failure and rpc never preserve or publish compile investigation`() {
        val results = listOf(
            RunResult(false, true, true, false),
            RunResult(false, false, false, true),
            RunResult(false, true, false, false),
            RunResult(true, true, true, false),
        )
        results.forEach { result ->
            val delegate = mock<CompileUiHandler>()
            val investigation = mock<AskAiInvestigation>()
            val console = mock<ConsoleView>()
            AskAiCompileUiHandler(delegate, investigationProject(), console, mock(), investigation, mock(), 123L).onEnd(result)
            Mockito.verify(delegate).onEnd(result)
            Mockito.verifyNoInteractions(investigation, console)
        }
        val rpc = mock<CompileUiHandler>()
        whenever(rpc.isRpcMode).thenReturn(true)
        val investigation = mock<AskAiInvestigation>()
        val console = mock<ConsoleView>()
        AskAiCompileUiHandler(rpc, mock(), console, mock(), investigation, mock(), 123L).onEnd(RunResult.FAILED)
        Mockito.verify(rpc).onEnd(RunResult.FAILED)
        Mockito.verifyNoInteractions(investigation, console)
    }

    private fun createInvestigation(project: Project, paths: JuggPathManager): AskAiInvestigation {
        val context = mock<ICompileContext>()
        val contexts = mock<CompileContextManager>()
        whenever(contexts.compileContext).thenReturn(context)
        val files = mock<DeployFileManager>()
        return AskAiInvestigation(project, paths, mock(), contexts, files, mock(), mock(), mock(), mock(),
            JuggLogger.getInstance(project, "AskAiInvestigation"))
    }

    @Test
    fun `failed snapshot contains final exception in full file logs before completion`() {
        val project = investigationProject()
        val paths = JuggPathManager(File(project.basePath!!), globalJuggRootDir = temporaryFolder.newFolder())
        JuggLogger.unregister(project)
        JuggLogger.register(project, paths.logDir)
        val investigation = createInvestigation(project, paths)
        val delegate = mock<CompileUiHandler>()
        val compiler = JuggLogger.getInstance(project, "Compiler")
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.buildTarget).thenReturn(BuildTarget.APP)
        val handler = investigation.wrapCompileUiHandler(delegate, mock(), options, 123L)
        repeat(1000) { compiler.debug("Queued compiler detail $it") }
        compiler.warn("Final compile exception", IllegalStateException("missing symbol"))
        var savedLogs = ""
        Mockito.doAnswer {
            val directory = paths.investigationDir.listFiles()!!.single()
            savedLogs = File(directory, "logs").listFiles()!!.joinToString("\n") { it.readText() }
            null
        }.`when`(delegate).onEnd(RunResult.FAILED)

        handler.onEnd(RunResult.FAILED)
        SwingUtilities.invokeAndWait { }

        assertTrue(savedLogs.contains("Queued compiler detail 999"))
        assertTrue(savedLogs.contains("Final compile exception"))
        assertTrue(savedLogs.contains("IllegalStateException: missing symbol"))
        val snapshot = paths.investigationDir.listFiles()!!.single()
        assertFalse(File(snapshot, "task-output.log").readText().contains("IllegalStateException: missing symbol"))
        val manifest = JsonParser.parseString(File(snapshot, "manifest.json").readText()).asJsonObject
        assertFalse(manifest.getAsJsonObject("failures").has("logs-flush"))
        Mockito.verify(delegate).onEnd(RunResult.FAILED)
    }

    @Test
    fun `interrupted log flush keeps other evidence and reports the incomplete log boundary`() {
        val project = investigationProject()
        val paths = JuggPathManager(File(project.basePath!!), globalJuggRootDir = temporaryFolder.newFolder())
        val state = File(paths.projectInfosDir, "project.json")
        state.parentFile.mkdirs()
        state.writeText("{\"state\":\"preserved\"}")
        val investigation = createInvestigation(project, paths)
        val options = mock<JuggGradleCompileOptions>()
        whenever(options.buildTarget).thenReturn(BuildTarget.APP)
        val snapshot = try {
            Thread.currentThread().interrupt()
            investigation.preserveCompileFailure(options, RunResult.FAILED, 123L, "compiler error")
        } finally {
            Thread.interrupted()
        }

        assertNotNull(snapshot)
        val directory = snapshot!!.directory
        val manifest = JsonParser.parseString(File(directory, "manifest.json").readText()).asJsonObject
        assertTrue(manifest.getAsJsonObject("failures")["logs-flush"].asString.contains("InterruptedException"))
        assertTrue(File(directory, "state").listFiles()!!.single().readText().contains("preserved"))
        assertEquals("compiler error", File(directory, "task-output.log").readText())
    }
}
