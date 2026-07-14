package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.IncrementalCompilerHelper
import com.sickworm.intellij.jugg.compiler.source.DexFileMerger
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.createImmediateTestTaskRunnerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class DeployFileManagerDexMergeTest {

    private val taskRunnerManager = createImmediateTestTaskRunnerManager()

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
                taskRunnerManager = taskRunnerManager,
                logger = logger,
            )

            deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)
            val historyDex = createDexOutputs("his", File(testRoot, "history"), 500)
            setDeployedFiles(deployFileManager, historyDex)

            val stagingDex = createDexOutputs("sta", File(testRoot, "staging"), DeployDataPlanner.MAX_DEPLOYED_DEX_COUNT - 500 + 1)
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
                taskRunnerManager = taskRunnerManager,
                logger = logger,
            )

            deployFileManager.init(emptyList(), emptyList(), resetFilesBeforeTimeMill = null)
            val historyDex = createDexOutputs("his", File(testRoot, "history"), 500)
            setDeployedFiles(deployFileManager, historyDex)

            val firstRoundDex = createDexOutputs("first", File(testRoot, "staging_round_1"), DeployDataPlanner.MAX_DEPLOYED_DEX_COUNT - 500 + 50)
            deployFileManager.addStagingFiles(firstRoundDex)
            val firstDeployData = deployFileManager.getDeployData()
            deployFileManager.commit(firstDeployData)
            assertEquals(1, mergeCallCount.get())

            val secondRoundDex = createDexOutputs("sec", File(testRoot, "staging_round_2"), 1)
            deployFileManager.addStagingFiles(secondRoundDex)
            deployFileManager.getDeployData()
            assertEquals(1, mergeCallCount.get())
        }
    }

    @Test
    fun testMergedDexKeepsUnionTargetApkPaths() {
        val testRoot = Files.createTempDirectory("deploy-dex-merge-test-3").toFile()
        Mockito.mockConstruction(DexFileMerger::class.java) { mock, _ ->
            Mockito.doAnswer { invocation ->
                val outputDir = invocation.arguments[1] as File
                outputDir.mkdirs()
                File(outputDir, "classes_merged.dex").writeBytes(byteArrayOf(0x64, 0x65, 0x78))
                null
            }.`when`(mock).merge(
                ArgumentMatchers.anyList<File>() ?: emptyList(),
                ArgumentMatchers.any(File::class.java) ?: File(""),
            )
        }.use {
            val outputDir = File(testRoot, "merged")
            val dexOutputs = createDexOutputs("target", File(testRoot, "dex"), 2)
                .mapIndexed { index, output ->
                    output.copy(
                        apkPath = "/base.apk",
                        targetApkPaths = if (index == 0) {
                            listOf("/base.apk")
                        } else {
                            listOf("/test.apk")
                        },
                    )
                }
            val compileResult = CompileResult.empty(CompileStatusHolder.DEFAULT).copy(outputs = dexOutputs)

            val mergedOutputs = IncrementalCompilerHelper.mergeDex(logger, compileResult, outputDir)!!.outputs

            assertEquals(listOf("/base.apk", "/test.apk"), mergedOutputs.single().targetApkPaths)
            assertEquals("/base.apk", mergedOutputs.single().apkPath)
        }
    }

    private fun createDexOutputs(prefix: String, baseDir: File, count: Int): List<CompileOutput> {
        val dexBytes = getValidDexBytes()
        return (0 until count).map { index ->
            val file = File(baseDir, "classes_${prefix}_$index.dex").also {
                it.parentFile.mkdirs()
                it.writeBytes(dexBytes)
            }
            CompileOutput(CompileOutput.Type.Dex, file, baseDir)
        }
    }

    private fun getValidDexBytes(): ByteArray {
        val dexFile = File(
            "../android_demo_project/build/app/intermediates/project_dex_archive/debug/out/androidx/databinding/DataBindingComponent.dex"
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
