package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.source.DexFileMerger
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class DeployFileManagerDexMergeTest {

    private val immediateRunner = object : IBackgroundTaskRunner {
        override fun runBackgroundSafe(jobName: String, action: Runnable): Job {
            action.run()
            return Job()
        }

        override fun runBackgroundSafe(jobName: String, delayMs: Long, action: Runnable): Job {
            action.run()
            return Job()
        }
    }

    @Test
    fun testGetDeployDataTriggerDexMergeAndRebuildDeployData() {
        val mergeCallCount = AtomicInteger(0)
        val testRoot = Files.createTempDirectory("deploy-dex-merge-test-1").toFile()
        val pathManager = JuggPathManager(testRoot)
        Mockito.mockConstruction(DexFileMerger::class.java) { mock, _ ->
            Mockito.doAnswer { invocation ->
                mergeCallCount.incrementAndGet()
                val outputDir = invocation.arguments[1] as File
                outputDir.mkdirs()
                File(outputDir, "classes_merged.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                null
            }.`when`(mock).merge(
                ArgumentMatchers.anyList<File>() ?: emptyList(),
                ArgumentMatchers.any(File::class.java) ?: File(""),
            )
        }.use {
            val deployFileManager = DeployFileManager(
                pathManager = pathManager,
                backgroundTaskRunner = immediateRunner,
                logger = logger,
            )

            deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)
            val historyDex = createDexOutputs(File(testRoot, "history"), 300)
            setDeployedFiles(deployFileManager, historyDex)

            val stagingDex = createDexOutputs(File(testRoot, "staging"), 201)
            val manifest = createResOutput(
                baseDir = File(testRoot, "staging_res"),
                relativePath = "AndroidManifest.xml",
                apkPath = "/base.apk",
            )
            deployFileManager.addStagingFiles(stagingDex + manifest)

            val deployData = deployFileManager.getDeployData()
            assertEquals(1, mergeCallCount.get())
            assertTrue(deployData.newClasses.isEmpty())
            assertTrue(deployData.hotFixModifiedClasses.isEmpty())
            assertTrue(deployData.hotReloadModifiedClasses.isEmpty())
            assertTrue(deployData.isNeedUpdateApk)
            assertEquals(
                setOf("AndroidManifest.xml", "classes_merged"),
                deployData.updateApkFiles.map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun testMergedHistoryDexShouldNotCountInNextRound() {
        val mergeCallCount = AtomicInteger(0)
        val testRoot = Files.createTempDirectory("deploy-dex-merge-test-2").toFile()
        val pathManager = JuggPathManager(testRoot)
        Mockito.mockConstruction(DexFileMerger::class.java) { mock, _ ->
            Mockito.doAnswer { invocation ->
                mergeCallCount.incrementAndGet()
                val outputDir = invocation.arguments[1] as File
                outputDir.mkdirs()
                File(outputDir, "classes_merged_${mergeCallCount.get()}.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                null
            }.`when`(mock).merge(
                ArgumentMatchers.anyList<File>() ?: emptyList(),
                ArgumentMatchers.any(File::class.java) ?: File(""),
            )
        }.use {
            val deployFileManager = DeployFileManager(
                pathManager = pathManager,
                backgroundTaskRunner = immediateRunner,
                logger = logger,
            )

            deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)
            val historyDex = createDexOutputs(File(testRoot, "history"), 350)
            setDeployedFiles(deployFileManager, historyDex)

            val firstRoundDex = createDexOutputs(File(testRoot, "staging_round_1"), 200)
            deployFileManager.addStagingFiles(firstRoundDex)
            val firstDeployData = deployFileManager.getDeployData()
            deployFileManager.commit(firstDeployData)
            assertEquals(1, mergeCallCount.get())

            val secondRoundDex = createDexOutputs(File(testRoot, "staging_round_2"), 1)
            deployFileManager.addStagingFiles(secondRoundDex)
            deployFileManager.getDeployData()
            assertEquals(1, mergeCallCount.get())
        }
    }

    private fun createDexOutputs(baseDir: File, count: Int): List<CompileOutput> {
        val dexBytes = getValidDexBytes()
        return (0 until count).map { index ->
            val file = File(baseDir, "classes_$index.dex").also {
                it.parentFile.mkdirs()
                it.writeBytes(dexBytes)
            }
            CompileOutput(CompileOutput.Type.Dex, file, baseDir)
        }
    }

    private fun getValidDexBytes(): ByteArray {
        val dexFile = File(
            "../android_demo_project/app/build/intermediates/project_dex_archive/debug/out/androidx/databinding/DataBindingComponent.dex"
        ).absoluteFile.normalize()
        return dexFile.readBytes()
    }

    private fun createResOutput(baseDir: File, relativePath: String, apkPath: String): CompileOutput {
        val file = File(baseDir, relativePath).also {
            it.parentFile.mkdirs()
            it.writeText("<manifest/>")
        }
        return CompileOutput(CompileOutput.Type.Res, file, baseDir, apkPath = apkPath)
    }

    private fun setDeployedFiles(deployFileManager: DeployFileManager, outputs: List<CompileOutput>) {
        deployFileManager.replaceDeployedFilesForTest(outputs)
    }
}
