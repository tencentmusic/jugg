package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Verifies file checksums across the former large-file shortcut boundary. */
class FileUtilsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `large files with the same length have different checksums when content changes`() {
        val first = temporaryFolder.newFile("first.so")
        val second = temporaryFolder.newFile("second.so")
        for ((file, value) in listOf(first to 1, second to 2)) {
            RandomAccessFile(file, "rw").use {
                it.setLength(100_000_001L)
                it.seek(100_000_000L)
                it.writeByte(value)
            }
        }

        assertEquals(first.length(), second.length())
        assertNotEquals(first.crc32, second.crc32)
    }

    @Test
    fun `small file checksum remains a standard CRC32`() {
        val file = temporaryFolder.newFile("small.txt").apply { writeText("123456789") }

        assertEquals(3421780262L, file.crc32)
    }
}
