package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessOutputReaderTest {

    @Test
    fun readLine_shouldDecodeUtf8Chinese_onWindows() {
        val reader = createReader("警告\r\n".toByteArray(Charsets.UTF_8), isWindows = true)

        assertEquals("警告", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun readLine_shouldFallbackToGbkChinese_onWindows() {
        val reader = createReader("警告\r\n".toByteArray(GBK), isWindows = true)

        assertEquals("警告", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun readLine_shouldDecodeMixedWindowsOutput_perLine() {
        val bytes = "GBK 警告".toByteArray(GBK) +
            "\r\n".toByteArray() +
            "UTF-8 警告".toByteArray(Charsets.UTF_8) +
            "\nASCII".toByteArray()
        val reader = createReader(bytes, isWindows = true)

        assertEquals("GBK 警告", reader.readLine())
        assertEquals("UTF-8 警告", reader.readLine())
        assertEquals("ASCII", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun readLine_shouldKeepUtf8_onNonWindows() {
        val reader = createReader("警告\n".toByteArray(Charsets.UTF_8), isWindows = false)

        assertEquals("警告", reader.readLine())
    }

    private fun createReader(bytes: ByteArray, isWindows: Boolean): ProcessOutputReader {
        return ProcessOutputReader(ByteArrayInputStream(bytes), isWindows)
    }

    companion object {
        private val GBK: Charset = Charset.forName("GBK")
    }
}
