package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.manager.changeAndRevert
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.*

class DeployHistoryManagerTest {

    private val gitManager = GitManager(assetsAndroidDir)

    @Before
    fun checkoutDir() {
        clearBuild()
        Runtime.getRuntime().exec("git checkout $assetsAndroidDir").waitFor()
        gitManager.deleteGit()
    }

    @Test
    fun test() {
        val storageDir = buildDir
        val historyManager = DeployHistoryManager(projectInfo.projectRoot, storageDir, logger)

        gitManager.deleteGit()
        assertFalse(historyManager.isRecoverFeatureAvailable)
        assertFalse(historyManager.hasBeenFullCompiled)

        gitManager.init()
        assertTrue(historyManager.isRecoverFeatureAvailable)
        assertFalse(historyManager.hasBeenFullCompiled)
        assertNull(historyManager.tryGetContextRecoverInfoFromDb())

        gitManager.addAllAndCommit("first commit")
        assertFalse(historyManager.hasBeenFullCompiled)
        assertNull(historyManager.tryGetContextRecoverInfoFromDb())

        val jugg = MockJugg()
        jugg.compileContextManager.initProjectInfo()
        historyManager.reInitAfterFullCompiled(projectInfo.apkInfos, jugg.compileContextManager.compileContext.modules)
        assertTrue(historyManager.hasBeenFullCompiled)
        assertTrue(historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.isEmpty() == true)

        changeAndRevert("MainActivity2.changeMethodReturn.java" to "MainActivity2.java") { files ->
            val changedFiles = files.map { file ->
                ChangedFile(CompileFile.Type.Java, file, File(""), mockModule)
            }
            assertEquals(1, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)
            historyManager.updateHistoryOnAfterDeployed(changedFiles, emptyList())
            assertEquals(0, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)

            val deployHistoryFile = File(storageDir, "deploy_history.db/deploy_history.json")
            val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
            assertNotNull(deployHistoryData?.fullCompileGitCommitHash)
            assertEquals(DeployHistoryData(
                deployHistoryData?.fullCompileGitCommitHash,
                1,
                mapOf("app/src/main/java/com/example/myapplication/MainActivity2.java" to 2808648208),
                1,
            ), deployHistoryData)
        }

        changeAndRevert("MainActivity2.addMethod.java" to "MainActivity2.java") { files ->
            val changedFiles = files.map { file ->
                ChangedFile(CompileFile.Type.Java, file, File(""), mockModule)
            }
            assertEquals(1, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)
            historyManager.updateHistoryOnAfterDeployed(changedFiles, emptyList())
            assertEquals(0, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)

            val deployHistoryFile = File(storageDir, "deploy_history.db/deploy_history.json")
            val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
            assertNotNull(deployHistoryData?.fullCompileGitCommitHash)
            assertEquals(DeployHistoryData(
                deployHistoryData?.fullCompileGitCommitHash,
                2,
                mapOf("app/src/main/java/com/example/myapplication/MainActivity2.java" to 1715140577),
                1,
            ), deployHistoryData)
        }

        gitManager.deleteGit()
    }
}