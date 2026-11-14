package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SyncLocalClasspathCommandTest {

    @Test
    fun `aggregate R jars survive baseline sync for app and androidTest variants`() {
        assumeTrue(isMac)
        RsyncCompatibleHelper.init(logger)
        val root = Files.createTempDirectory("jugg-sync-r").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val destination = File(root, "baseline").apply { mkdirs() }
            val modules = listOf("debug", "debugAndroidTest").map {
                ModuleBuildPathInfo(source, File(source, "app"), it, buildDirRelativePath = "")
            }
            val rFiles = modules.flatMap { module ->
                listOf("compile_and_runtime_r_class_jar", "compile_and_runtime_not_namespaced_r_class_jar").map { layout ->
                    val rFile = File(module.buildDir, "intermediates/$layout/${module.buildVariant}/processResources/R.jar")
                    rFile.parentFile.mkdirs()
                    rFile.writeText("$layout:${module.buildVariant}")
                    rFile
                }
            }
            File(source, "unrelated.txt").writeText("not a build baseline")

            assertEquals(0, CmdExecutor(logger).invoke(SyncLocalClasspathCommand(source, destination, modules)))

            val syncedRoot = File(destination, source.name)
            rFiles.forEach { assertEquals(it.readText(), File(syncedRoot, it.relativeTo(source).path).readText()) }
            assertFalse(File(syncedRoot, "unrelated.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun macPathsWithSpaces_shouldExecuteSyncCommand() {
        assumeTrue(isMac)
        RsyncCompatibleHelper.init(logger)
        val tempDir = Files.createTempDirectory("jugg-sync-test").toFile()
        try {
            val sourceDir = File(tempDir, "Source Project").also { it.mkdirs() }
            val destinationDir = File(tempDir, "Classpath Backup").also { it.mkdirs() }
            val command = SyncLocalClasspathCommand(sourceDir, destinationDir, emptyList())

            assertEquals(0, CmdExecutor(logger).invoke(command))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
