package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.git.GitManager
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
    @After
    fun checkoutDir() {
        clearBuild()
        Runtime.getRuntime().exec("git checkout $assetsAndroidDir").waitFor()
        gitManager.deleteGit()
    }

    @Test
    fun test() {
        val storageDir = buildDir
        val historyManager = DeployHistoryManager(gitManager, storageDir, logger)

        gitManager.deleteGit()
        assertFalse(historyManager.isAvailable)

        gitManager.init()
        assertTrue(historyManager.isAvailable)
        assertNull(historyManager.getChangedFilesSinceLastDeployed())

        gitManager.addAllAndCommit("first commit")
        assertNull(historyManager.getChangedFilesSinceLastDeployed())

        historyManager.onAfterFullCompiled()
        assertTrue(historyManager.getChangedFilesSinceLastDeployed()?.isEmpty() == true)

        changeAndRevert("MainActivity2.changeMethodReturn.java" to "MainActivity2.java") { files ->
            val changedFiles = files.map { file ->
                ChangedFile(CompileFile.Type.Java, file, File(""), ModuleInfo.NO_MODULE)
            }
            assertEquals(1, historyManager.getChangedFilesSinceLastDeployed()?.size)
            historyManager.onAfterDeployed(changedFiles)
            assertEquals(0, historyManager.getChangedFilesSinceLastDeployed()?.size)

            val deployHistoryFile = File(storageDir, "deploy_history.json")
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
                ChangedFile(CompileFile.Type.Java, file, File(""), ModuleInfo.NO_MODULE)
            }
            assertEquals(1, historyManager.getChangedFilesSinceLastDeployed()?.size)
            historyManager.onAfterDeployed(changedFiles)
            assertEquals(0, historyManager.getChangedFilesSinceLastDeployed()?.size)

            val deployHistoryFile = File(storageDir, "deploy_history.json")
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