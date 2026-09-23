package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class ApkFileModifierStreamTest {

    @Test
    fun `file replacement inherits compression methods in a mixed update`() {
        val dir = Files.createTempDirectory("jugg-apk-stream").toFile()
        val apk = File(dir, "app.apk")
        writeZip(
            apk,
            mapOf(
                "lib/arm64-v8a/liblarge.so" to EntryData("old-native".toByteArray(), ZipEntry.DEFLATED),
                "resources.arsc" to EntryData("old-resources".toByteArray(), ZipEntry.STORED),
                "assets/value.txt" to EntryData("old-asset".toByteArray(), ZipEntry.DEFLATED),
            ),
        )
        val native = File(dir, "liblarge.so").apply { writeText("new-native") }
        val resources = "new-resources".toByteArray()
        val asset = "new-asset".toByteArray()
        val modifier = modifier(apk)

        modifier.addFile("lib/arm64-v8a/liblarge.so", native, native.length(), crc32(native.readBytes()))
        modifier.addFile("resources.arsc", resources)
        modifier.addFile("assets/value.txt", asset)
        modifier.updateDirectly()

        ZipFile(apk).use { zip ->
            val nativeEntry = zip.getEntry("lib/arm64-v8a/liblarge.so")
            val resourceEntry = zip.getEntry("resources.arsc")
            val assetEntry = zip.getEntry("assets/value.txt")
            assertEquals(ZipEntry.DEFLATED, nativeEntry.method)
            assertEquals(ZipEntry.STORED, resourceEntry.method)
            assertEquals(ZipEntry.STORED, assetEntry.method)
            assertArrayEquals(native.readBytes(), zip.getInputStream(nativeEntry).readBytes())
            assertArrayEquals(resources, zip.getInputStream(resourceEntry).readBytes())
            assertArrayEquals(asset, zip.getInputStream(assetEntry).readBytes())
        }
    }

    @Test
    fun `missing file payload keeps the original apk`() {
        val dir = Files.createTempDirectory("jugg-apk-missing-source").toFile()
        val apk = File(dir, "app.apk")
        writeZip(
            apk,
            mapOf("lib/arm64-v8a/liblarge.so" to EntryData("old-native".toByteArray(), ZipEntry.DEFLATED)),
        )
        val original = apk.readBytes()
        val native = File(dir, "liblarge.so").apply { writeText("new-native") }
        val modifier = modifier(apk)
        modifier.addFile("lib/arm64-v8a/liblarge.so", native, native.length(), crc32(native.readBytes()))
        native.delete()

        assertFailsWith<IllegalStateException> { modifier.updateDirectly() }
        assertArrayEquals(original, apk.readBytes())
    }

    @Test
    fun `byte only update keeps the ZipFS stored behavior`() {
        if (Runtime.version().version()[0] < 14) {
            return
        }
        val dir = Files.createTempDirectory("jugg-apk-bytes").toFile()
        val apk = File(dir, "app.apk")
        writeZip(apk, mapOf("assets/value.txt" to EntryData("old".toByteArray(), ZipEntry.DEFLATED)))
        val modifier = modifier(apk)

        modifier.addFile("assets/value.txt", "new".toByteArray())
        modifier.updateDirectly()

        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/value.txt")
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals("new", zip.getInputStream(entry).bufferedReader().readText())
        }
    }

    @Test
    fun `large file payload requires an existing base apk entry`() {
        val dir = Files.createTempDirectory("jugg-apk-large-missing-entry").toFile()
        val apk = File(dir, "app.apk")
        writeZip(apk, mapOf("assets/value.txt" to EntryData("old".toByteArray(), ZipEntry.DEFLATED)))
        val original = apk.readBytes()
        val native = File(dir, "liblarge.so")
        RandomAccessFile(native, "rw").use { it.setLength(Int.MAX_VALUE + 1L) }
        val modifier = modifier(apk)

        try {
            modifier.addFile("lib/arm64-v8a/liblarge.so", native, native.length(), 0L)

            assertFailsWith<IllegalStateException> { modifier.updateDirectly() }
            assertArrayEquals(original, apk.readBytes())
        } finally {
            native.delete()
        }
    }

    @Test
    fun `classic zip entry limit rejects file payload before writing`() {
        val dir = Files.createTempDirectory("jugg-apk-classic-zip-limit").toFile()
        val apk = File(dir, "app.apk")
        writeZip(apk, emptyMap())
        val native = File(dir, "liblarge.so")
        RandomAccessFile(native, "rw").use { it.setLength(4_294_967_296L) }
        val modifier = modifier(apk)

        try {
            assertFailsWith<IllegalStateException> {
                modifier.addFile("lib/arm64-v8a/liblarge.so", native, native.length(), 0L)
            }
        } finally {
            native.delete()
        }
    }

    private fun modifier(apk: File): ApkFileModifier {
        return ApkFileModifier(
            apkFile = apk,
            signConfig = null,
            androidHome = apk.parentFile,
            logger = Mockito.mock(Logger::class.java),
        )
    }

    private fun writeZip(file: File, entries: Map<String, EntryData>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, data) ->
                val entry = ZipEntry(name).apply {
                    method = data.method
                    if (method == ZipEntry.STORED) {
                        size = data.bytes.size.toLong()
                        crc = crc32(data.bytes)
                    }
                }
                output.putNextEntry(entry)
                output.write(data.bytes)
                output.closeEntry()
            }
        }
        assertTrue(file.isFile)
    }

    private fun crc32(bytes: ByteArray): Long {
        return CRC32().run {
            update(bytes)
            value
        }
    }

    private data class EntryData(val bytes: ByteArray, val method: Int)
}
