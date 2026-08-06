package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.deploy.api.Deploy
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads embedded dolls from the Android Studio deploy installer matryoshka on host.
 */
class InstallerMatryoshkaReader(
    private val installersRoot: String,
    private val logger: Logger,
) {

    fun extractAgentSo(deviceAbi: String, arch: Deploy.Arch): ByteArray {
        val dollName = InstallerAgentDollNames.resolve(deviceAbi, arch)
        val installer = File(installersRoot, "$deviceAbi/installer")
        if (!installer.isFile) {
            fail("installer not found at ${installer.absolutePath}")
        }
        return try {
            val bytes = MatryoshkaFileReader.readDoll(installer, dollName)
                ?: fail("doll $dollName not found in ${installer.absolutePath}")
            if (bytes.isEmpty()) {
                fail("doll $dollName is empty in ${installer.absolutePath}")
            }
            bytes
        } catch (e: DirectOverlayDeployFailedException) {
            throw e
        } catch (e: Exception) {
            fail("failed to read matryoshka from ${installer.absolutePath}", e)
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing {
        val detail = "Direct overlay deploy failed: $message"
        logger.warn(detail, cause)
        throw DirectOverlayDeployFailedException(detail, cause)
    }
}

/**
 * Maps deploy arch and host installer ABI to the matryoshka doll name used by AS installer packaging.
 * agent-alt.so exists only in 64-bit installers (arm64-v8a / x86_64) for 32-bit apps on 64-bit devices.
 */
internal object InstallerAgentDollNames {
    private val INSTALLER_64_BIT_ABIS = setOf("arm64-v8a", "x86_64")

    fun resolve(deviceAbi: String, arch: Deploy.Arch): String {
        return if (deviceAbi in INSTALLER_64_BIT_ABIS && arch == Deploy.Arch.ARCH_32_BIT) {
            "agent-alt.so"
        } else {
            "agent.so"
        }
    }
}

/** Thrown when direct overlay prerequisites fail and deploy should recover via Apply Changes. */
class DirectOverlayDeployFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object MatryoshkaConstants {
    const val MAGIC: Long = 0xd1d50655L
}

/**
 * Parses matryoshka dolls appended to the tail of AS deploy installer binaries.
 */
internal object MatryoshkaFileReader {

    fun readDoll(installer: File, dollName: String): ByteArray? {
        RandomAccessFile(installer, "r").use { file ->
            if (file.length() < 8) {
                return null
            }
            var cursor = file.length()
            val magic = readBackInt(file, cursor) ?: return null
            cursor -= 4
            if (magic.toLong() and 0xFFFF_FFFFL != MatryoshkaConstants.MAGIC) {
                return null
            }
            val dollCount = readBackInt(file, cursor) ?: return null
            cursor -= 4
            repeat(dollCount) {
                val nameLen = readBackInt(file, cursor) ?: return null
                cursor -= 4
                val nameBytes = readBackBytes(file, cursor, nameLen) ?: return null
                cursor -= nameLen.toLong()
                val name = String(nameBytes, Charsets.UTF_8)
                val fileSize = readBackInt(file, cursor) ?: return null
                cursor -= 4
                if (name == dollName) {
                    return readBackBytes(file, cursor, fileSize)
                }
                cursor -= fileSize.toLong()
            }
            return null
        }
    }

    private fun readBackInt(file: RandomAccessFile, endExclusive: Long): Int? {
        if (endExclusive < 4) {
            return null
        }
        val bytes = readBackBytes(file, endExclusive, 4) ?: return null
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readBackBytes(file: RandomAccessFile, endExclusive: Long, size: Int): ByteArray? {
        if (size < 0 || endExclusive < size) {
            return null
        }
        val start = endExclusive - size
        file.seek(start)
        val buffer = ByteArray(size)
        val read = file.read(buffer)
        return if (read == size) buffer else null
    }
}
