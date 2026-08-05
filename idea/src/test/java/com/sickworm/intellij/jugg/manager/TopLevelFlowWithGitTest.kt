package com.sickworm.intellij.jugg.manager

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.mock.ProjectInfo
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.io.File
import kotlin.test.*

class TopLevelFlowWithGitTest {

    companion object {
        @ClassRule @JvmField val deviceRule = RequiresDeviceRule()
    }

    @Before
    fun resetAllState() {
        Runtime.getRuntime().exec("git checkout ${projectInfo.projectRoot}").waitFor()
        GitManager(projectInfo.projectRoot).deleteGit()
        MockJugg().pathManager.juggRootDir.deleteRecursively()
    }

    @Test
    fun initDeployWithoutGit() {
        GitManager(projectInfo.projectRoot).init()
        val jugg = MockJugg()
        jugg.resetAllState()

        // initial state
        assertEquals(JuggDeployState.State.READY_FULL_COMPILE, jugg.deployStateManager.deployState.state)
        assertFalse(jugg.deployHistoryManager.isRecoverFeatureAvailable)
        assertFalse(jugg.deployHistoryManager.hasBeenFullCompiled)
        assertNull(jugg.deployHistoryManager.tryGetContextRecoverInfoFromDb())

        // full compile
        jugg.deploy()

        // deployable state
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg.deployStateManager.deployState.state)
        assertFalse(jugg.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg.deployHistoryManager.hasBeenFullCompiled)
        assertNull(jugg.deployHistoryManager.tryGetContextRecoverInfoFromDb())

        // unrecoverable state
        val jugg2 = MockJugg()
        assertEquals(JuggDeployState.State.READY_FULL_COMPILE, jugg2.deployStateManager.deployState.state)
        assertFalse(jugg2.deployHistoryManager.isRecoverFeatureAvailable)
        assertFalse(jugg2.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo2 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNull(recoverInfo2)
    }

    @Test
    fun initDeployWithGit() {
        createInitializedGitJugg()
    }

    private fun createInitializedGitJugg(): MockJugg {
        val gitManager = GitManager(projectInfo.projectRoot)
        assertFalse(gitManager.hasInitGit)
        gitManager.init()
        gitManager.addAllAndCommit("first commit")
        assertTrue(gitManager.hasInitGit)
        val jugg = MockJugg()
        jugg.resetAllState()

        // initial state
        assertEquals(JuggDeployState.State.READY_FULL_COMPILE, jugg.deployStateManager.deployState.state)
        assertTrue(jugg.deployHistoryManager.isRecoverFeatureAvailable)
        assertFalse(jugg.deployHistoryManager.hasBeenFullCompiled)
        assertNull(jugg.deployHistoryManager.tryGetContextRecoverInfoFromDb())

        // full compile
        jugg.deploy()

        // deployable state
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg.deployStateManager.deployState.state)
        assertTrue(jugg.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg.deployHistoryManager.hasBeenFullCompiled)

        val recoverInfo = jugg.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo)
        assertEquals(0, recoverInfo.deployedFiles.size)
        return jugg
    }

    @Test
    fun recoveryDeployWithGit() {
        val jugg = createInitializedGitJugg()

        // first deploy
        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
        assertEquals(1, jugg.deployFileManager.getStagingFiles().size)
        jugg.deployCompiledApp()
        assertEquals(0, jugg.deployFileManager.getStagingFiles().size)

        // check state after first deploy
        val recoverInfo = jugg.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo)
        assertEquals(1, recoverInfo.deployedFiles.size)

        // recoverable state after renew Jugg
        val jugg2 = MockJugg()
        jugg2.loadFromHistory()
        jugg2.juggManager.updateDeployState()
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg2.deployStateManager.deployState.state)
        assertTrue(jugg2.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg2.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo2 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo2)
        assertEquals(1, recoverInfo2.deployedFiles.size)
        assertEquals(0, jugg2.deployFileManager.getStagingFiles().size)

        // second deploy
        jugg2.changeFileAndNotify("MainActivity.kt" to "MainActivity.kt")
        jugg2.checkCompileResult("MainActivity.kt",
            hotFixModifiedClassesSize = 1, hotReloadModifiedClassesSize = 4)
        jugg2.deployCompiledApp()

        // check state after second deploy
        val recoverInfo3 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo3)
        assertEquals(6, recoverInfo3.deployedFiles.size)

        // recoverable state after renew Jugg
        val jugg3 = MockJugg()
        jugg3.loadFromHistory()
        jugg3.juggManager.updateDeployState()
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg3.deployStateManager.deployState.state)
        assertTrue(jugg3.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg3.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo4 = jugg3.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo4)
        assertEquals(6, recoverInfo4.deployedFiles.size)
        assertEquals(0, jugg3.deployFileManager.getStagingFiles().size)
    }

    @Test
    fun recoveryDeployFromVersion1CompileContextWithGit() {
        val originalProjectInfo = projectInfo
        projectInfo = ProjectInfo(
            packageName = originalProjectInfo.packageName,
            projectRootDir = originalProjectInfo.projectRootDir,
            modifiedSourceDir = originalProjectInfo.modifiedSourceDir,
            apkPath = "app/build/outputs/apk/debug/app-debug.apk",
            apkEntryInfo = originalProjectInfo.apkEntryInfo,
        )
        try {
            val jugg = createInitializedGitJugg()
            jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
            jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
            jugg.deploy()
            rewriteModuleBuildInfoAsVersion1(jugg)

            val recoveredJugg = MockJugg()
            recoveredJugg.loadFromHistory()
            recoveredJugg.juggManager.updateDeployState()

            assertEquals(JuggDeployState.State.READY_DEPLOY, recoveredJugg.deployStateManager.deployState.state)
            assertTrue(recoveredJugg.deployHistoryManager.hasBeenFullCompiled)
        } finally {
            projectInfo = originalProjectInfo
        }
    }

    @Test
    fun recoveryDeployOnIsReadyIncCompileState() {
        val jugg = createInitializedGitJugg()

        // first deploy
        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
        jugg.deployCompiledApp()

        // set app not launched
        AdbCmdHelper(jugg.deployTargetManager.getSelectedDevices().first(), logger).stopApp(projectInfo.packageName)

        // recoverable state after renew Jugg
        println("\n\nstart deploy 2")
        val jugg2 = MockJugg()
        jugg2.loadFromHistory()
        jugg2.juggManager.updateDeployState()
        assertEquals(JuggDeployState.State.READY_INCREMENTAL_COMPILE, jugg2.deployStateManager.deployState.state)
        assertTrue(jugg2.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg2.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo2 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo2)
        assertEquals(1, recoverInfo2.deployedFiles.size)
        assertEquals(0, jugg2.deployFileManager.getStagingFiles().size)

        println("\n\nstart deploy 3")
        assertTrue(jugg2.deployTargetManager.startApp(jugg2.deployTargetManager.getSelectedDevices().first()))
        jugg2.waitingLaunchAppAndCheck()
        jugg2.juggManager.updateDeployState()
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg2.deployStateManager.deployState.state)
    }

    @Test
    fun incrementalCompileFailureKeepsFallbackStateAfterGitRefresh() {
        val jugg = MockJugg()
        val gitManager = GitManager(jugg.projectDir)
        gitManager.init()
        gitManager.addAllAndCommit("first commit")
        jugg.dryFullCompile()
        val sourceFile = File(
            jugg.projectDir,
            "app/src/main/java/com/example/myapplication/MainActivity.kt",
        )

        changeAndRevert(
            sourceFile,
            "Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)",
            "MissingReference()",
        ) {
            jugg.notifyFileChanges(listOf(sourceFile))
            jugg.compileChangedFiles()

            val failedFile = jugg.deployFileManager.getUncompiledFiles().single()
            assertEquals(1, failedFile.compiledTimes)
            assertTrue(jugg.deployFileManager.isNoFileChanges())
        }
    }

    private fun rewriteModuleBuildInfoAsVersion1(jugg: MockJugg) {
        val moduleBuildInfoFile = File(jugg.pathManager.compileContextDbDir, "module_builds.json")
        val root = JsonParser.parseString(moduleBuildInfoFile.readText()).asJsonObject
        root.addProperty("version", 1)
        root.getAsJsonObject("modulePathInfos").entrySet().forEach { (_, module) ->
            module.asJsonObject.remove("buildDirRelativePath")
        }
        moduleBuildInfoFile.writeText(root.toString())
    }
}
