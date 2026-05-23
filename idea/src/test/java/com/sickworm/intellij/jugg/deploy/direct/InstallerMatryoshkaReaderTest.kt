package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deploy.proto.Deploy
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InstallerMatryoshkaReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logger = TestGlobal.getLogger()

    @Test
    fun `extractAgentSo should return agent doll for 64 bit arch`() {
        val root = tempFolder.newFolder("installers")
        val agentBytes = byteArrayOf(0x01, 0x02, 0x03)
        writeInstaller(root, dolls = mapOf("agent.so" to agentBytes, "version" to "1".toByteArray()))

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        val extracted = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)

        assertArrayEquals(agentBytes, extracted)
    }

    @Test
    fun `extractAgentSo should return agent-alt doll for 32 bit arch`() {
        val root = tempFolder.newFolder("installers")
        val altBytes = byteArrayOf(0x0A, 0x0B)
        writeInstaller(
            root,
            dolls = mapOf(
                "agent.so" to byteArrayOf(0x01),
                "agent-alt.so" to altBytes,
            ),
        )

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        val extracted = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_32_BIT)

        assertArrayEquals(altBytes, extracted)
    }

    @Test(expected = DirectOverlayDeployFailedException::class)
    fun `extractAgentSo should throw direct deploy failed when installer missing`() {
        val root = tempFolder.newFolder("installers")
        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)

        try {
            reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)
        } catch (e: DirectOverlayDeployFailedException) {
            assertTrue(e.message.orEmpty().contains("Direct overlay"))
            throw e
        }
    }

    @Test(expected = DirectOverlayDeployFailedException::class)
    fun `extractAgentSo should throw direct deploy failed when doll missing`() {
        val root = tempFolder.newFolder("installers")
        writeInstaller(root, dolls = mapOf("version" to "1".toByteArray()))

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)
    }

    @Test(expected = DirectOverlayDeployFailedException::class)
    fun `extractAgentSo should throw direct deploy failed when matryoshka magic invalid`() {
        val root = tempFolder.newFolder("installers")
        val abiDir = File(root, "arm64-v8a").also { it.mkdirs() }
        File(abiDir, "installer").writeBytes("not-a-matryoshka".toByteArray())

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)
    }

    private fun writeInstaller(root: File, dolls: Map<String, ByteArray>, deviceAbi: String = "arm64-v8a") {
        val abiDir = File(root, deviceAbi).also { it.mkdirs() }
        val installer = File(abiDir, "installer")
        installer.writeBytes("fake-elf".toByteArray())
        appendMatryoshka(installer, dolls)
    }

    private fun appendMatryoshka(installer: File, dolls: Map<String, ByteArray>) {
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
