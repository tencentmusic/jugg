package com.sickworm.intellij.jugg.gradle.compile

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads process output as raw line bytes and adapts UTF-8/GBK output on Windows.
 */
internal class ProcessOutputReader(
    inputStream: InputStream,
    private val isWindows: Boolean,
) : Closeable {

    private val input = PushbackInputStream(BufferedInputStream(inputStream), 1)

    fun readLine(): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            when (val value = input.read()) {
                -1 -> return if (output.size() == 0) null else decode(output.toByteArray())
                '\n'.code -> return decode(output.toByteArray())
                '\r'.code -> {
                    val next = input.read()
                    if (next != -1 && next != '\n'.code) {
                        input.unread(next)
                    }
                    return decode(output.toByteArray())
                }
                else -> output.write(value)
            }
        }
    }

    override fun close() {
        input.close()
    }

    private fun decode(bytes: ByteArray): String {
        if (!isWindows || bytes.all { it >= 0 }) {
            return bytes.toString(Charsets.UTF_8)
        }
        return decodeUtf8Strictly(bytes) ?: bytes.toString(GBK_CHARSET)
    }

    private fun decodeUtf8Strictly(bytes: ByteArray): String? {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        private val GBK_CHARSET: Charset = if (Charset.isSupported("GBK")) {
            Charset.forName("GBK")
        } else {
            Charsets.UTF_8
        }
    }
}
