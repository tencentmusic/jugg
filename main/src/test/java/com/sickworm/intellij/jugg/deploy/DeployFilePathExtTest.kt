package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `small native library keeps the byte backed path`() {
        val baseDir = Files.createTempDirectory("jugg-small-native-output").toFile()
        val native = writeFile(baseDir, "lib/arm64-v8a/libsmall.so", byteArrayOf(1, 2, 3))
        val output = CompileOutput(
            type = CompileOutput.Type.NativeLib,
            file = native,
            baseDir = baseDir,
            apkPath = "/base.apk",
        )

        val item = output.toDeployItem()

        assertFalse(item.isFileBacked)
        assertEquals(3L, item.size)
        assertContentEquals(byteArrayOf(1, 2, 3), item.content)
    }

    @Test
    fun `file backed native library rejects byte array access`() {
        val native = Files.createTempFile("jugg-large-native", ".so").toFile()
        RandomAccessFile(native, "rw").use { it.setLength(Int.MAX_VALUE + 1L) }
        val item = DeployItem.fileBackedNativeLib(
            name = "lib/arm64-v8a/liblarge.so",
            checksum = 1L,
            file = native,
            apkPath = "/base.apk",
        )

        try {
            assertTrue(item.isFileBacked)
            assertEquals(Int.MAX_VALUE + 1L, item.size)
            assertEquals(native, item.sourceFileOrNull())
            assertFailsWith<IllegalStateException> { item.content }
        } finally {
            native.delete()
        }
    }

    @Test
    fun `oversized non native output still fails before reading`() {
        Assume.assumeFalse("creating a sparse file larger than 2 GiB is not portable", isWindows)
        val baseDir = Files.createTempDirectory("jugg-oversized-asset-output").toFile()
        val asset = File(baseDir, "assets/huge.dat").apply {
            parentFile.mkdirs()
            RandomAccessFile(this, "rw").use { it.setLength(Int.MAX_VALUE + 1L) }
        }
        val output = CompileOutput(
            type = CompileOutput.Type.Asset,
            file = asset,
            baseDir = baseDir,
            apkPath = "/base.apk",
        )

        try {
            assertFailsWith<JuggInternalException> { output.toDeployItem() }
        } finally {
            asset.delete()
        }
    }

    private fun writeFile(baseDir: File, relativePath: String, bytes: ByteArray): File {
        return File(baseDir, relativePath).also {
            it.parentFile.mkdirs()
            it.writeBytes(bytes)
        }
    }
}
