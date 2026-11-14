package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.copyResource
import com.sickworm.intellij.jugg.compiler.isLinux
import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Generates bounded-memory native patches with bundled tools and verifies their content identities. */
class NativeLibraryDelta(
    private val compileUiHandler: CompileUiHandler,
    loggerArg: Logger,
    private val deployHistoryManager: IDeployHistoryManager,
) {
    private val logger = loggerArg.getInstance("NativeLibraryDelta")
    // Resolve persisted snapshots once per deploy run, after compilation may have overwritten staging.
    private val baselines by lazy {
        deployHistoryManager.getDeployedData().orEmpty()
            .filter { it.type == CompileOutput.Type.NativeLib }
            .associate { (it.apkPath to it.relativeFile.invariantSeparatorsPath) to it.file }
    }

    internal fun baseline(apkPath: String, name: String): File? =
        baselines[apkPath to name]?.takeIf { it.isFile }

    /** Carries the patch and the exact source/target identities used at the device boundary. */
    data class Patch(val file: File, val oldSha256: String, val newSha256: String)

    /** Returns null when the host is unsupported or sending the patch would not save bytes. */
    fun createPatch(old: File, new: File, workDir: File): Patch? {
        checkCanceled()
        val tool = hostTool("hdiffz") ?: return null
        val patch = File(workDir, "native.patch")
        val start = System.currentTimeMillis()
        logger.debug("Generating large SO delta: ${new.name}, size=${new.length()} bytes.")
        runTool(listOf(tool.path, "-s-1k", "-SD", "-c-zlib-1", "-p-2", "-d",
            old.absolutePath, new.absolutePath, patch.absolutePath), File(workDir, "diff.log"))
        logger.debug("Large SO delta generated: ${patch.length()}/${new.length()} bytes, " +
                "cost=${System.currentTimeMillis() - start}ms.")
        if (!patch.isFile || patch.length() == 0L) throw IOException("HDiffPatch produced no patch")
        if (patch.length() >= new.length()) return null
        val hashStart = System.currentTimeMillis()
        val result = Patch(patch, sha256(old), sha256(new))
        logger.debug("Large SO local content checksums ready, cost=${System.currentTimeMillis() - hashStart}ms.")
        return result
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun hostTool(name: String): File? {
        val arch = System.getProperty("os.arch").lowercase()
        val platform = when {
            isMac && arch in setOf("aarch64", "arm64", "x86_64", "amd64") -> "macos"
            isLinux && arch in setOf("aarch64", "arm64") -> "linux_arm64"
            isLinux && arch in setOf("amd64", "x86_64") -> "linux64"
            isWindows && arch in setOf("amd64", "x86_64") -> "windows64"
            else -> return null
        }
        return tool("$platform/$name${if (isWindows) ".exe" else ""}")
    }

    fun deviceTool(abi: String): File? {
        if (abi !in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) return null
        return tool("android/$abi/hpatchz")
    }

    private fun runTool(command: List<String>, output: File) {
        checkCanceled()
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) checkCanceled()
            checkCanceled()
            val text = output.inputStream().bufferedReader().use { it.readText().takeLast(8000) }
            logger.debug(text)
            if (process.exitValue() != 0) throw IOException("HDiffPatch failed (${process.exitValue()}): $text")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun checkCanceled() {
        if (compileUiHandler.isCanceled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Large SO delta canceled")
        }
    }

    companion object {
        const val VERSION = "5.1.3"

        @Synchronized
        private fun tool(relativePath: String): File? {
            val resource = "/tools/hdiffpatch/$VERSION/$relativePath"
            if (NativeLibraryDelta::class.java.getResource(resource) == null) return null
            return copyResource(resource)
        }
    }
}
