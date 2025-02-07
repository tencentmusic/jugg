package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.mock.RequiresDevice
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Before
import org.junit.Test
import kotlin.test.*

@RequiresDevice
class TopLevelFlowWithGitTest {

    @Before
    fun resetAllState() {
        Runtime.getRuntime().exec("git checkout ${projectInfo.projectRoot}").waitFor()
        GitManager(projectInfo.projectRoot).deleteGit()
        MockJugg().pathManager.juggRootDir.deleteRecursively()
    }

    @Test
    fun initDeployWithoutGit() {
        val jugg = MockJugg()

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
        val jugg = MockJugg()
        initDeployWithGit(jugg)
    }

    private fun initDeployWithGit(jugg: MockJugg) {
        // init git
        val gitManager = GitManager(jugg.projectDir)
        assertFalse(gitManager.hasInitGit)
        gitManager.init()
        gitManager.addAllAndCommit("first commit")
        assertTrue(gitManager.hasInitGit)

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
    }

    @Test
    fun recoveryDeployWithGit() {
        val jugg = MockJugg()
        initDeployWithGit(jugg)

        // first deploy
        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
        assertEquals(1, jugg.deployFileManager.getStagingFiles().size)
        jugg.deploy()
        assertEquals(0, jugg.deployFileManager.getStagingFiles().size)

        // check state after first deploy
        val recoverInfo = jugg.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo)
        assertEquals(1, recoverInfo.deployedFiles.size)

        // recoverable state after renew Jugg
        val jugg2 = MockJugg()
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg2.deployStateManager.deployState.state)
        assertTrue(jugg2.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg2.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo2 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo2)
        assertEquals(1, recoverInfo2.deployedFiles.size)
        assertEquals(1, jugg2.deployFileManager.getStagingFiles().size)

        // recover
        jugg2.deploy()
        assertEquals(0, jugg2.deployFileManager.getStagingFiles().size)

        // second deploy
        jugg2.changeFileAndNotify("MainActivity.kt" to "MainActivity.kt")
        jugg2.checkCompileResult("MainActivity.kt",
            newClassesSize = 1, hotFixModifiedClassesSize = 1)
        jugg2.deploy()

        // check state after second deploy
        val recoverInfo3 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo3)
        assertEquals(3, recoverInfo3.deployedFiles.size)

        // recoverable state after renew Jugg
        val jugg3 = MockJugg()
        assertEquals(JuggDeployState.State.READY_DEPLOY, jugg3.deployStateManager.deployState.state)
        assertTrue(jugg3.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg3.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo4 = jugg3.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo4)
        assertEquals(3, recoverInfo4.deployedFiles.size)
        assertEquals(3, jugg3.deployFileManager.getStagingFiles().size)
    }

    @Test
    fun recoveryDeployOnIsReadyIncCompileState() {
        val jugg = MockJugg()
        initDeployWithGit(jugg)

        // first deploy
        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
        jugg.deploy()

        // set app not launched
        AdbCmdHelper(jugg.deployTargetManager.getSelectedDevices().first(), logger).stopApp(projectInfo.packageName)

        // recoverable state after renew Jugg
        println("\n\nstart deploy 2")
        val jugg2 = MockJugg()
        assertEquals(JuggDeployState.State.READY_INCREMENTAL_COMPILE, jugg2.deployStateManager.deployState.state)
        assertTrue(jugg2.deployHistoryManager.isRecoverFeatureAvailable)
        assertTrue(jugg2.deployHistoryManager.hasBeenFullCompiled)
        val recoverInfo2 = jugg2.deployHistoryManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo2)
        assertEquals(1, recoverInfo2.deployedFiles.size)
        assertEquals(1, jugg2.deployFileManager.getStagingFiles().size)

        println("\n\nstart deploy 3")
        jugg2.deploy()
        assertEquals(0, jugg2.deployFileManager.getStagingFiles().size)
    }
}
