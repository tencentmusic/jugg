package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeployFilePathExtTest {

    @Test
    fun `dex deploy item keeps all target APK paths`() {
        val baseDir = Files.createTempDirectory("jugg-dex-output").toFile()
        val dexFile = writeFile(baseDir, "classes.dex", byteArrayOf(0x64, 0x65, 0x78))
        val output = CompileOutput(
            type = CompileOutput.Type.Dex,
            file = dexFile,
            baseDir = baseDir,
            apkPath = "/base.apk",
            targetApkPaths = listOf("/base.apk", "/test.apk"),
        )

        val item = output.toDeployItem()

        assertEquals(DeployItem.FLAG_CLASS, item.apkPath)
        assertEquals(listOf("/base.apk", "/test.apk"), item.targetApkPaths)
        assertTrue(item.belongsTo("/base.apk"))
        assertTrue(item.belongsTo("/test.apk"))
        assertFalse(item.belongsTo("/other.apk"))
    }

    @Test
    fun `resource deploy item keeps all target APK paths`() {
        val baseDir = Files.createTempDirectory("jugg-res-output").toFile()
        val resFile = writeFile(baseDir, "res/layout/main.xml", "<View/>".toByteArray())
        val output = CompileOutput(
            type = CompileOutput.Type.Res,
            file = resFile,
            baseDir = baseDir,
            apkPath = "/base.apk",
            targetApkPaths = listOf("/base.apk", "/test.apk"),
        )

        val item = output.toDeployItem()

        assertEquals("/base.apk", item.apkPath)
        assertEquals(listOf("/base.apk", "/test.apk"), item.targetApkPaths)
        assertTrue(item.belongsTo("/base.apk"))
        assertTrue(item.belongsTo("/test.apk"))
        assertFalse(item.belongsTo("/other.apk"))
    }

    @Test
    fun `resource deploy item without explicit targets falls back to apkPath`() {
        val baseDir = Files.createTempDirectory("jugg-res-single-output").toFile()
        val resFile = writeFile(baseDir, "AndroidManifest.xml", "<manifest/>".toByteArray())
        val output = CompileOutput(
            type = CompileOutput.Type.Res,
            file = resFile,
            baseDir = baseDir,
            apkPath = "/base.apk",
        )

        val item = output.toDeployItem()

        assertEquals(listOf("/base.apk"), item.targetApkPaths)
        assertTrue(item.belongsTo("/base.apk"))
        assertFalse(item.belongsTo("/test.apk"))
    }

    @Test
    fun `deploy item normalizes apkPath into target apk paths`() {
        val item = DeployItem(
            name = "classes.dex",
            type = CompileOutput.Type.Dex,
            checksum = 1L,
            content = byteArrayOf(1),
            apkPath = "/base.apk",
            targetApkPaths = listOf("/test.apk"),
        )

        assertEquals(listOf("/test.apk", "/base.apk"), item.targetApkPaths)
    }

    private fun writeFile(baseDir: File, relativePath: String, bytes: ByteArray): File {
        return File(baseDir, relativePath).also {
            it.parentFile.mkdirs()
            it.writeBytes(bytes)
        }
    }
}
