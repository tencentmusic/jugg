package com.sickworm.intellij.jugg.deploy.direct

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object MatryoshkaFixtureWriter {
    fun appendMatryoshka(installer: File, dolls: Map<String, ByteArray>) {
        val payload = ByteArrayOutput()
        dolls.forEach { (name, content) ->
            payload.write(content)
            payload.writeInt(content.size)
            payload.write(name.toByteArray(Charsets.UTF_8))
            payload.writeInt(name.length)
        }
        payload.writeInt(dolls.size)
        payload.writeInt(MatryoshkaConstants.MAGIC.toInt())
        installer.appendBytes(payload.toByteArray())
    }

    private class ByteArrayOutput {
        private val buffer = mutableListOf<Byte>()

        fun write(bytes: ByteArray) {
            buffer.addAll(bytes.toList())
        }

        fun writeInt(value: Int) {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            buffer.addAll(bytes.toList())
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
