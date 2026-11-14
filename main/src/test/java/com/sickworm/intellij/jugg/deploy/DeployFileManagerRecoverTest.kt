package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.createImmediateTestTaskRunnerManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeployFileManagerRecoverTest {

    private val taskRunnerManager = createImmediateTestTaskRunnerManager()

    @Test
    fun initShouldDeleteExtractedClassesAndKeepOtherTemporaryFiles() {
        val testRoot = Files.createTempDirectory("deploy-extracted-classes-test").toFile()
        val pathManager = JuggPathManager(testRoot, globalJuggRootDir = File(testRoot, "global"))
        val extractedClassesDir = pathManager.extractedClassesDir
        val extractedClass = File(extractedClassesDir, "com/example/Dependency.class")
        val legacyClass = File(pathManager.tmpDir, "com/example/Legacy.class")
        val diagnosticsFile = File(pathManager.diagnosticsDir, "report/log.txt")
        listOf(extractedClass, legacyClass, diagnosticsFile).forEach {
            it.parentFile.mkdirs()
            it.writeText("preserved content")
        }
        val deployFileManager = DeployFileManager(pathManager, taskRunnerManager, logger)
        try {
            deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)

            assertFalse(extractedClassesDir.exists())
            assertEquals("preserved content", legacyClass.readText())
            assertEquals("preserved content", diagnosticsFile.readText())

            deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)
            assertFalse(extractedClassesDir.exists())
        } finally {
            deployFileManager.dispose()
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun constRefFailureShouldNotBlockDeployFileManagerFlow() {
        val testRoot = Files.createTempDirectory("deploy-const-ref-failure-test").toFile()
        val globalRoot = File(testRoot, "global")
        val pathManager = JuggPathManager(
            projectDir = testRoot,
            globalJuggRootDir = globalRoot,
        )
        pathManager.constRefSharedDbFile.apply {
            parentFile.mkdirs()
            writeText("not a sqlite database")
        }
        pathManager.repoFingerprintDbFile.mkdirs()
        val deployFileManager = DeployFileManager(
            pathManager = pathManager,
            taskRunnerManager = taskRunnerManager,
            logger = logger,
        )
        assertEquals("not a sqlite database", pathManager.constRefSharedDbFile.readText())

        deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)

        val sourceDir = File(testRoot, "src").apply { mkdirs() }
        val sourceFile = File(sourceDir, "Constants.kt").apply { writeText("const val VALUE = 1") }
        val moduleInfo = ModuleInfo.virtualModule.copy(sourceDirs = listOf(sourceDir))

        deployFileManager.updateModuleInfos(mapOf(moduleInfo.name to moduleInfo), mappingFile = null)
        deployFileManager.addChangedFile(
            listOf(
                ChangedFile(
                    type = CompileFile.Type.Kotlin,
                    file = sourceFile,
                    baseDir = sourceDir,
                    module = moduleInfo,
                )
            )
        )
        deployFileManager.awaitConstRefAnalysis(listOf(sourceFile.absolutePath))
        val recompileFiles = deployFileManager.getRecompileFiles(
            isMinified = false,
            isCompilingEffectedSourceFiles = false,
            classObfuscator = null,
        )
        deployFileManager.dispose()

        assertTrue(recompileFiles.effectedSourceFiles.isEmpty())
    }

    @Test
    fun resetAfterReinstallShouldKeepCurrentStagingDexWhenRecoveredHistoryLostApkMetadata() {
        val testRoot = Files.createTempDirectory("deploy-recover-duplicate-dex-test").toFile()
        val deployFileManager = DeployFileManager(
            pathManager = JuggPathManager(testRoot),
            taskRunnerManager = taskRunnerManager,
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
        deployFileManager.resetAfterReinstall(replayNativeLibraries = true)

        val deployData = deployFileManager.getDeployData()
        val classItems = deployData.newClasses + deployData.hotFixModifiedClasses + deployData.hotReloadModifiedClasses
        val sidebarItems = classItems.filter {
            it.name == "com.example.SidebarRedDotManager"
        }

        assertEquals(1, sidebarItems.size)
        assertEquals(listOf(newStagingDex.toDeployItem().checksum), sidebarItems.map { it.checksum })
    }

    @Test
    fun addStagingFilesShouldReplaceRecoveredDexWithoutApkMetadata() {
        val testRoot = Files.createTempDirectory("deploy-recover-then-compile-test").toFile()
        val deployFileManager = DeployFileManager(
            pathManager = JuggPathManager(testRoot),
            taskRunnerManager = taskRunnerManager,
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
        deployFileManager.resetAfterReinstall(replayNativeLibraries = true)
        deployFileManager.addStagingFiles(listOf(newStagingDex))

        assertEquals(listOf(newStagingDex), deployFileManager.getStagingFiles())
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
            taskRunnerManager = taskRunnerManager,
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
        deployFileManager.resetAfterReinstall(replayNativeLibraries = true)

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
