package com.sickworm.intellij.jugg.deploy.direct

import com.android.tools.deploy.proto.Deploy
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun `resolve should use agent-alt only for 32 bit app on 64 bit installer abi`() {
        assertEquals("agent-alt.so", InstallerAgentDollNames.resolve("arm64-v8a", Deploy.Arch.ARCH_32_BIT))
        assertEquals("agent-alt.so", InstallerAgentDollNames.resolve("x86_64", Deploy.Arch.ARCH_32_BIT))
        assertEquals("agent.so", InstallerAgentDollNames.resolve("arm64-v8a", Deploy.Arch.ARCH_64_BIT))
        assertEquals("agent.so", InstallerAgentDollNames.resolve("armeabi-v7a", Deploy.Arch.ARCH_32_BIT))
        assertEquals("agent.so", InstallerAgentDollNames.resolve("x86", Deploy.Arch.ARCH_32_BIT))
    }

    @Test
    fun `extractAgentSo should return agent doll for 64 bit arch`() {
        val root = tempFolder.newFolder("installers")
        val agentBytes = elfPayload(InstallerMatryoshkaFixture.AGENT_64_ELF_HEADER, marker = 0x01)
        writeInstaller(root, dolls = mapOf("agent.so" to agentBytes, "version" to "1".toByteArray()))

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        val extracted = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)

        assertArrayEquals(agentBytes, extracted)
    }

    @Test
    fun `extractAgentSo should return agent-alt doll for 32 bit app on 64 bit device abi`() {
        val root = tempFolder.newFolder("installers")
        val altBytes = elfPayload(InstallerMatryoshkaFixture.AGENT_ALT_ELF_HEADER, marker = 0x0A)
        writeInstaller(
            root,
            dolls = InstallerMatryoshkaFixture.arm64Dolls(
                agentBytes = elfPayload(InstallerMatryoshkaFixture.AGENT_64_ELF_HEADER, marker = 0x01),
                agentAltBytes = altBytes,
            ),
        )

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        val extracted = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_32_BIT)

        assertArrayEquals(altBytes, extracted)
    }

    @Test
    fun `extractAgentSo should return agent doll for 32 bit app on 32 bit device abi`() {
        val root = tempFolder.newFolder("installers")
        val agentBytes = elfPayload(InstallerMatryoshkaFixture.AGENT_32_ELF_HEADER, marker = 0x21)
        writeInstaller(
            root,
            deviceAbi = "armeabi-v7a",
            dolls = InstallerMatryoshkaFixture.arm32Dolls(agentBytes = agentBytes),
        )

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        val extracted = reader.extractAgentSo(deviceAbi = "armeabi-v7a", arch = Deploy.Arch.ARCH_32_BIT)

        assertArrayEquals(agentBytes, extracted)
    }

    @Test
    fun `extractAgentSo should parse arm64 fixture with real doll order and elf headers`() {
        val root = tempFolder.newFolder("installers")
        val agentBytes = elfPayload(InstallerMatryoshkaFixture.AGENT_64_ELF_HEADER, marker = 0x64)
        val altBytes = elfPayload(InstallerMatryoshkaFixture.AGENT_ALT_ELF_HEADER, marker = 0x32)
        writeInstaller(
            root,
            dolls = InstallerMatryoshkaFixture.arm64Dolls(agentBytes = agentBytes, agentAltBytes = altBytes),
        )

        val reader = InstallerMatryoshkaReader(root.absolutePath, logger)
        val extractedAgent = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)
        val extractedAlt = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_32_BIT)

        assertTrue(extractedAgent.copyOfRange(0, 4).contentEquals(InstallerMatryoshkaFixture.ELF_MAGIC))
        assertTrue(extractedAlt.copyOfRange(0, 4).contentEquals(InstallerMatryoshkaFixture.ELF_MAGIC))
        assertEquals(0x64.toByte(), extractedAgent[16])
        assertEquals(0x32.toByte(), extractedAlt[16])
    }

    @Test
    fun `extractAgentSo should read agent dolls from local as installer when available`() {
        val asRoot = File("/Applications/Android Studio.app/Contents/plugins/android/resources/installer")
        assumeTrue("local AS installer not found", File(asRoot, "arm64-v8a/installer").isFile)

        val reader = InstallerMatryoshkaReader(asRoot.absolutePath, logger)
        val agent64 = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_64_BIT)
        val agentAlt = reader.extractAgentSo(deviceAbi = "arm64-v8a", arch = Deploy.Arch.ARCH_32_BIT)
        val agent32Device = reader.extractAgentSo(deviceAbi = "armeabi-v7a", arch = Deploy.Arch.ARCH_32_BIT)

        assertTrue(agent64.copyOfRange(0, 4).contentEquals(InstallerMatryoshkaFixture.ELF_MAGIC))
        assertTrue(agentAlt.copyOfRange(0, 4).contentEquals(InstallerMatryoshkaFixture.ELF_MAGIC))
        assertTrue(agent32Device.copyOfRange(0, 4).contentEquals(InstallerMatryoshkaFixture.ELF_MAGIC))
        assertTrue(agent64.size > 1_000_000)
        assertTrue(agentAlt.size > 1_000_000)
        assertTrue(agent32Device.size > 1_000_000)
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

    private fun elfPayload(header: ByteArray, marker: Int, totalSize: Int = 64): ByteArray {
        val payload = ByteArray(totalSize)
        header.copyInto(payload)
        payload[16] = marker.toByte()
        return payload
    }

    private fun writeInstaller(
        root: File,
        dolls: Map<String, ByteArray>,
        deviceAbi: String = "arm64-v8a",
    ) {
        val abiDir = File(root, deviceAbi).also { it.mkdirs() }
        val installer = File(abiDir, "installer")
        installer.writeBytes(InstallerMatryoshkaFixture.FAKE_ELF_PREFIX)
        MatryoshkaFixtureWriter.appendMatryoshka(installer, dolls)
    }
}

/** Fixture data aligned with AS deploy/installer/BUILD matryoshka doll order. */
internal object InstallerMatryoshkaFixture {
    val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    // Headers captured from local AS arm64-v8a/installer tail payloads.
    val AGENT_64_ELF_HEADER = byteArrayOf(
        0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
    val AGENT_ALT_ELF_HEADER = byteArrayOf(
        0x7f, 0x45, 0x4c, 0x46, 0x01, 0x01, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
    val AGENT_32_ELF_HEADER = AGENT_ALT_ELF_HEADER

    val FAKE_ELF_PREFIX = byteArrayOf(
        0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00,
    )

    fun arm64Dolls(agentBytes: ByteArray, agentAltBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        return linkedMapOf(
            "agent-alt.so" to agentAltBytes,
            "agent.so" to agentBytes,
            "coroutine_debugger_agent.so" to elfStub(AGENT_32_ELF_HEADER, marker = 0x11),
            "coroutine_debugger_agent64.so" to elfStub(AGENT_64_ELF_HEADER, marker = 0x12),
            "install_server" to elfStub(AGENT_64_ELF_HEADER, marker = 0x13),
            "version" to "dced2491".toByteArray(),
        )
    }

    fun arm32Dolls(agentBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        return linkedMapOf(
            "agent.so" to agentBytes,
            "coroutine_debugger_agent.so" to elfStub(AGENT_32_ELF_HEADER, marker = 0x21),
            "install_server" to elfStub(AGENT_32_ELF_HEADER, marker = 0x22),
            "version" to "dced2491".toByteArray(),
        )
    }

    private fun elfStub(header: ByteArray, marker: Int): ByteArray {
        val payload = ByteArray(64)
        header.copyInto(payload)
        payload[16] = marker.toByte()
        return payload
    }
}

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
