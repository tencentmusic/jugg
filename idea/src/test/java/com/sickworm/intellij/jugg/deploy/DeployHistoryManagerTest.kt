package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.manager.changeAndRevert
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.FileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.*

class DeployHistoryManagerTest {

    private val gitManager = GitManager(assetsAndroidDir)
    private val pathManager = JuggPathManager(projectInfo.projectRoot)
    private val fileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, logger)

    @Before
    fun checkoutDir() {
        clearBuild()
        pathManager.databaseDir.clearDir()
        Runtime.getRuntime().exec("git checkout $assetsAndroidDir").waitFor()
        gitManager.deleteGit()
    }

    @Test
    fun testHistoryDb() {
        val storageDir = pathManager.databaseDir
        gitManager.init() // we need init first after GitManager can search parent directory
        val historyManager = DeployHistoryManager(pathManager, fileChangesHandler, logger)

        gitManager.deleteGit()
        assertFalse(historyManager.isRecoverFeatureAvailable)
        assertFalse(historyManager.hasBeenFullCompiled)

        gitManager.init()
        assertFalse(historyManager.isRecoverFeatureAvailable)
        assertFalse(historyManager.hasBeenFullCompiled)
        assertNull(historyManager.tryGetContextRecoverInfoFromDb())

        gitManager.addAllAndCommit("first commit")
        assertTrue(historyManager.isRecoverFeatureAvailable)
        assertFalse(historyManager.hasBeenFullCompiled)
        assertNull(historyManager.tryGetContextRecoverInfoFromDb())

        historyManager.reInitAfterFullCompiled(projectInfo.apkInfos, mapOf(mockModule.name to mockModule), System.currentTimeMillis())
        assertTrue(historyManager.hasBeenFullCompiled)
        val recoverInfo1 = historyManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo1)
        assertTrue(recoverInfo1.deployedFiles.isEmpty())
        assertTrue(recoverInfo1.changedFiles.isEmpty())
        assertEquals(
            ApkInfoSerializer().serialize(projectInfo.projectRoot, projectInfo.apkInfos),
            ApkInfoSerializer().serialize(projectInfo.projectRoot, recoverInfo1.compileContextInfo.apkInfos),
        )
        assertTrue(recoverInfo1.compileContextInfo.moduleBuildPathInfos.isNotEmpty())

        changeAndRevert("MainActivity2.changeMethodReturn.java" to "MainActivity2.java") { files ->
            val changedFiles = files.map { file ->
                ChangedFile(CompileFile.Type.Java, file, File(assetsAndroidDir, "app/src/main/java"), mockModule)
            }
            assertEquals(1, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)
            historyManager.beforeIncrementalCompile(changedFiles)
            historyManager.updateHistoryOnAfterDeployed(emptyList())
            assertEquals(0, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)

            val deployHistoryFile = File(storageDir, "deploy_history.db/deploy_history.json")
            val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
            assertNotNull(deployHistoryData?.fullCompileGitCommitHash)
            assertEquals(DeployHistoryData(
                deployHistoryData?.fullCompileGitCommitHash,
                emptyMap(),
                1,
                mapOf("app/src/main/java/com/example/myapplication/MainActivity2.java".systemBasedPath to
                        if (isWindows) 901992344 else 2808648208 // "/r/n" vs "/n"
                ),
                2,
            ), deployHistoryData)
        }

        changeAndRevert("MainActivity2.addMethod.java" to "MainActivity2.java") { files ->
            val changedFiles = files.map { file ->
                ChangedFile(CompileFile.Type.Java, file, File(assetsAndroidDir, "app/src/main/java"), mockModule)
            }
            assertEquals(1, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)
            historyManager.beforeIncrementalCompile(changedFiles)
            historyManager.updateHistoryOnAfterDeployed(emptyList())
            assertEquals(0, historyManager.tryGetContextRecoverInfoFromDb()?.changedFiles?.size)

            val deployHistoryFile = File(storageDir, "deploy_history.db/deploy_history.json")
            val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
            assertNotNull(deployHistoryData?.fullCompileGitCommitHash)
            assertEquals(DeployHistoryData(
                deployHistoryData?.fullCompileGitCommitHash,
                emptyMap(),
                2,
                mapOf("app/src/main/java/com/example/myapplication/MainActivity2.java".systemBasedPath to
                        if (isWindows) 3934764329 else 1715140577 // "/r/n" vs "/n"
                ),
                2,
            ), deployHistoryData)
        }
    }

    @Test
    fun testDeployDb() {
        val storageDir = pathManager.databaseDir
        gitManager.init() // we need init first after GitManager can search parent directory
        val historyManager = DeployHistoryManager(pathManager, fileChangesHandler, logger)

        gitManager.deleteGit()
        gitManager.init()
        gitManager.addAllAndCommit("first commit")
        historyManager.reInitAfterFullCompiled(projectInfo.apkInfos, mapOf(mockModule.name to mockModule), System.currentTimeMillis())

        val deployedFile = File(buildDir, "com/A.dex").let {
            it.parentFile.mkdirs()
            it.createNewFile()
            CompileOutput(CompileOutput.Type.Dex, it, buildDir)
        }
        val storageFile = File(storageDir, "compile_context.db/deployed/classes/com/A.dex")
        assertFalse(storageFile.exists())

        val recoverInfo = historyManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfo)
        assertEquals(0, recoverInfo.deployedFiles.size)
        historyManager.beforeIncrementalCompile(emptyList())
        historyManager.updateHistoryOnAfterDeployed(listOf(deployedFile))
        val recoverInfoNew = historyManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfoNew)
        assertEquals(1, recoverInfoNew.deployedFiles.size)
        assertEquals(storageFile, recoverInfoNew.deployedFiles.first().file)
        assertTrue(storageFile.exists())

        val deployedFile2 = File(buildDir, "drawable/B.xml").let {
            it.parentFile.mkdirs()
            it.createNewFile()
            CompileOutput(CompileOutput.Type.Res, it, buildDir)
        }
        val storageFile2 = File(storageDir, "compile_context.db/deployed/res/drawable/B.xml")
        assertFalse(storageFile2.exists())
        historyManager.beforeIncrementalCompile(emptyList())
        historyManager.updateHistoryOnAfterDeployed(listOf(deployedFile2))
        val recoverInfoNew2 = historyManager.tryGetContextRecoverInfoFromDb()
        assertNotNull(recoverInfoNew2)
        assertEquals(2, recoverInfoNew2.deployedFiles.size)
        assertTrue(storageFile2.exists())
    }

    @Test
    fun testFilterUnchangedFiles() {
        gitManager.init() // we need init first after GitManager can search parent directory
        val historyManager = DeployHistoryManager(pathManager, fileChangesHandler, logger)

        gitManager.deleteGit()
        gitManager.init()
        gitManager.addAllAndCommit("first commit")
        historyManager.reInitAfterFullCompiled(projectInfo.apkInfos, mapOf(mockModule.name to mockModule), System.currentTimeMillis())

        val targetFile = File(projectInfo.projectRoot, "app/src/main/java/com/example/myapplication/MainActivity2.java")
        var result = historyManager.filterUnchangedFiles(listOf(targetFile))
        assertEquals(1, result.size)

        changeAndRevert(
            "MainActivity2.crossReference.java" to "MainActivity2.java",
        ) { _ ->
            result = historyManager.filterUnchangedFiles(listOf(targetFile))
            assertEquals(0, result.size)
        }

        val ignoreFile = File(projectInfo.projectRoot, "local.properties")
        result = historyManager.filterUnchangedFiles(listOf(ignoreFile))
        assertEquals(1, result.size)
        result = historyManager.filterUnchangedFiles(listOf(ignoreFile, targetFile))
        assertEquals(2, result.size)

        changeAndRevert(
            "MainActivity2.crossReference.java" to "MainActivity2.java",
        ) { _ ->
            result = historyManager.filterUnchangedFiles(listOf(targetFile, ignoreFile))
            assertEquals(1, result.size)
        }

        val settingsGradle = File(projectInfo.projectRoot, "settings.gradle")
        result = historyManager.filterUnchangedFiles(listOf(settingsGradle))
        assertEquals(0, result.size)
    }
}