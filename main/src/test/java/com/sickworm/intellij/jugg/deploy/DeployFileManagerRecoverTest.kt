package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeployFileManagerRecoverTest {

    private val immediateRunner = object : IBackgroundTaskRunner {
        override fun runBackgroundSafe(jobName: String, isNeedLog: Boolean, action: Runnable): Job {
            action.run()
            return Job()
        }

        override fun runBackgroundSafe(jobName: String, delayMs: Long, isNeedLog: Boolean, action: Runnable): Job {
            action.run()
            return Job()
        }
    }

    @Test
    fun resetAfterReinstallShouldKeepCurrentStagingDexWhenRecoveredHistoryLostApkMetadata() {
        val testRoot = Files.createTempDirectory("deploy-recover-duplicate-dex-test").toFile()
        val deployFileManager = DeployFileManager(
            pathManager = JuggPathManager(testRoot),
            backgroundTaskRunner = immediateRunner,
            logger = logger,
        )
        deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)

        val relativePath = "com/example/SidebarRedDotManager.dex"
        val oldRecoveredDex = createDexOutput(
            baseDir = File(testRoot, "deployed"),
            relativePath = relativePath,
            fixtureName = "R.dex",
            apkPath = null,
        )
        val newStagingDex = createDexOutput(
            baseDir = File(testRoot, "staging"),
            relativePath = relativePath,
            fixtureName = "R\$dimen.dex",
            apkPath = "/base.apk",
        )

        deployFileManager.replaceDeployedFilesForTest(listOf(oldRecoveredDex))
        deployFileManager.addStagingFiles(listOf(newStagingDex))
        deployFileManager.resetAfterReinstall()

        val deployData = deployFileManager.getDeployData()
        val classItems = deployData.newClasses + deployData.hotFixModifiedClasses + deployData.hotReloadModifiedClasses
        val sidebarItems = classItems.filter {
            it.name == "com.example.SidebarRedDotManager"
        }

        assertEquals(1, sidebarItems.size)
        assertEquals(listOf(newStagingDex.toDeployItem().checksum), sidebarItems.map { it.checksum })
    }

    @Test
    fun resetAfterReinstallShouldKeepExplicitDexWithSameRelativePathForDifferentTargetApks() {
        val testRoot = Files.createTempDirectory("deploy-recover-multi-apk-dex-test").toFile()
        val deployFileManager = DeployFileManager(
            pathManager = JuggPathManager(testRoot),
            backgroundTaskRunner = immediateRunner,
            logger = logger,
        )
        deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)

        val relativePath = "com/example/SharedClass.dex"
        val baseDex = createDexOutput(
            baseDir = File(testRoot, "deployed"),
            relativePath = relativePath,
            fixtureName = "R.dex",
            apkPath = "/base.apk",
        )
        val testDex = createDexOutput(
            baseDir = File(testRoot, "staging"),
            relativePath = relativePath,
            fixtureName = "R\$dimen.dex",
            apkPath = "/test.apk",
        )

        deployFileManager.replaceDeployedFilesForTest(listOf(baseDex))
        deployFileManager.addStagingFiles(listOf(testDex))
        deployFileManager.resetAfterReinstall()

        val deployData = deployFileManager.getDeployData()
        val classItems = deployData.newClasses + deployData.hotFixModifiedClasses + deployData.hotReloadModifiedClasses
        val sharedItems = classItems.filter {
            it.name == "com.example.SharedClass"
        }

        assertEquals(
            listOf(testDex.toDeployItem().checksum, baseDex.toDeployItem().checksum),
            sharedItems.map { it.checksum },
        )
    }

    private fun createDexOutput(
        baseDir: File,
        relativePath: String,
        fixtureName: String,
        apkPath: String?,
    ): CompileOutput {
        val file = File(baseDir, relativePath).apply {
            parentFile.mkdirs()
            writeBytes(dexFixture(fixtureName).readBytes())
        }
        return CompileOutput(
            type = CompileOutput.Type.Dex,
            file = file,
            baseDir = baseDir,
            apkPath = apkPath,
        )
    }

    private fun dexFixture(name: String): File {
        return File("../idea/src/test/assets/dex/com/example/myapplication/$name").absoluteFile.normalize()
    }
}
