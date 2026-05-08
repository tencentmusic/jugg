package com.sickworm.intellij.jugg.compiler

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import org.junit.Test

class CompileOutputTargetApkPathsTest {

    @Test
    fun `compile output target apk paths automatically includes apkPath`() {
        val baseDir = Files.createTempDirectory("jugg-output-target-apk").toFile()
        val outputFile = File(baseDir, "classes.dex").apply {
            parentFile.mkdirs()
            writeText("dex")
        }

        val output = CompileOutput(
            type = CompileOutput.Type.Dex,
            file = outputFile,
            baseDir = baseDir,
            apkPath = "/base.apk",
            targetApkPaths = listOf("/test.apk", "/base.apk"),
        )

        assertEquals(listOf("/test.apk", "/base.apk"), output.targetApkPaths)
    }

    @Test
    fun `compile output target apk paths normalizes apkPath into explicit targets`() {
        val baseDir = Files.createTempDirectory("jugg-output-target-apk-missing").toFile()
        val outputFile = File(baseDir, "classes.dex").apply {
            parentFile.mkdirs()
            writeText("dex")
        }

        val output = CompileOutput(
            type = CompileOutput.Type.Dex,
            file = outputFile,
            baseDir = baseDir,
            apkPath = "/base.apk",
            targetApkPaths = listOf("/test.apk"),
        )

        assertEquals(listOf("/test.apk", "/base.apk"), output.targetApkPaths)
    }
}
