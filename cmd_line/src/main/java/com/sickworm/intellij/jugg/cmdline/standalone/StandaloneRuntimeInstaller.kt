package com.sickworm.intellij.jugg.cmdline.standalone

import com.google.gson.Gson
import com.sickworm.intellij.jugg.ai.skills.PythonRuntimeResolver
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.tools.ToolProvider

/** Describes one complete standalone runtime snapshot. */
data class StandaloneRuntimeManifest(
    val schemaVersion: Int,
    val runtimeApiVersion: Int,
    val bootstrapApiVersion: Int,
    val targetVersion: String,
    val releaseBuildId: String,
    val releaseChannel: String,
    val toolingReleaseBuildId: String,
    val managedBy: String,
    val jarFileNames: List<String>,
    val jarSha256: MutableMap<String, String>,
    val bootstrapFileNames: List<String> = emptyList(),
    val bootstrapSha256: Map<String, String> = emptyMap(),
)

data class StandaloneBundle(val rootDir: File, val manifestFile: File, val manifest: StandaloneRuntimeManifest) {
    companion object {
        fun read(rootDir: File): StandaloneBundle {
            val manifestFile = rootDir.resolve("standalone_bundle_manifest.json")
            check(manifestFile.isFile) { "Standalone Bundle manifest is missing: $manifestFile" }
            val manifest = Gson().fromJson(manifestFile.readText(), StandaloneRuntimeManifest::class.java)
            check(manifest.jarFileNames.isNotEmpty()) { "Standalone Bundle runtime is empty" }
            return StandaloneBundle(rootDir, manifestFile, manifest)
        }
    }
}

/** Installs verified standalone snapshots and commits the active manifest atomically. */
class StandaloneRuntimeInstaller(private val juggRootDir: File, private val binDir: File) {
    val hotUpdateDir = juggRootDir.resolve("hot_update")
    val storageDir = hotUpdateDir.resolve("jars")
    private val activeManifestFile = hotUpdateDir.resolve("standalone_load_manifest.json")
    private val previousManifestFile = hotUpdateDir.resolve("standalone_previous_load_manifest.json")
    private val releasesDir = juggRootDir.resolve("standalone/releases")
    private val globalLockFile = juggRootDir.resolve("locks/global.lock")

    fun install(bundleDir: File, managedBy: String = "external", allowDowngrade: Boolean = false) {
        validateEnvironment()
        val bundle = StandaloneBundle.read(bundleDir)
        val manifest = bundle.manifest.copy(managedBy = managedBy)
        installValidated(StandaloneBundle(bundle.rootDir, bundle.manifestFile, manifest), allowDowngrade)
    }

    internal fun installValidated(bundle: StandaloneBundle, allowDowngrade: Boolean = false) = withGlobalLock {
        validateBundle(bundle)
        val current = readActiveManifest()
        if (!shouldActivate(current, bundle.manifest, allowDowngrade)) {
            check(bundle.manifest.managedBy == "idea" && current != null &&
                    current.toolingReleaseBuildId == bundle.manifest.toolingReleaseBuildId) {
                "Standalone runtime ${bundle.manifest.releaseBuildId} would downgrade or change the active channel"
            }
            publishTooling(bundle)
            installPythonCli(bundle.rootDir.resolve("cli"))
            installLaunchers(current)
            return@withGlobalLock
        }
        storageDir.mkdirs()
        bundle.manifest.jarFileNames.forEach { jarName ->
            publishImmutable(bundle.rootDir.resolve("jars/$jarName"), storageDir.resolve(jarName), bundle.manifest.jarSha256.getValue(jarName))
        }
        publishTooling(bundle)
        val releaseDir = releasesDir.resolve(bundle.manifest.releaseBuildId)
        releaseDir.mkdirs()
        writeAtomically(releaseDir.resolve("standalone_load_manifest.json"), bundle.manifest)
        if (current != null && current.releaseBuildId != bundle.manifest.releaseBuildId) {
            writeAtomically(previousManifestFile, current)
        }
        installPythonCli(bundle.rootDir.resolve("cli"))
        installLaunchers(bundle.manifest)
        writeAtomically(activeManifestFile, bundle.manifest)
    }

    fun rollback() = withGlobalLock {
        val previous = readPreviousManifest() ?: error("No previous standalone runtime is available")
        validateInstalledManifest(previous)
        readActiveManifest()?.let { writeAtomically(previousManifestFile, it) }
        writeAtomically(activeManifestFile, previous)
    }

    fun readActiveManifest(): StandaloneRuntimeManifest? = readManifest(activeManifestFile)

    fun readPreviousManifest(): StandaloneRuntimeManifest? = readManifest(previousManifestFile)

    internal fun shouldActivate(current: StandaloneRuntimeManifest?, candidate: StandaloneRuntimeManifest, allowDowngrade: Boolean): Boolean {
        if (current == null || allowDowngrade || current.releaseBuildId == candidate.releaseBuildId) return true
        if (current.releaseChannel != candidate.releaseChannel) return false
        val versionOrder = compareProductVersions(candidate.targetVersion, current.targetVersion)
        return versionOrder > 0 || versionOrder == 0 && candidate.releaseBuildId > current.releaseBuildId
    }

    private fun validateEnvironment() {
        check(Runtime.version().feature() >= 11 && ToolProvider.getSystemJavaCompiler() != null) {
            "A complete JDK 11+ is required to install Jugg standalone"
        }
        PythonRuntimeResolver.requireCommand()
    }

    private fun compareProductVersions(first: String, second: String): Int {
        val firstParts = first.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val secondParts = second.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(firstParts.size, secondParts.size)) { index ->
            val result = (firstParts.getOrNull(index) ?: 0).compareTo(secondParts.getOrNull(index) ?: 0)
            if (result != 0) return result
        }
        return 0
    }

    private fun validateBundle(bundle: StandaloneBundle) {
        val manifest = bundle.manifest
        check(manifest.schemaVersion == 1 && manifest.runtimeApiVersion == 1 && manifest.bootstrapApiVersion == 1) {
            "Unsupported standalone Bundle API"
        }
        check(manifest.releaseBuildId.isNotBlank() && manifest.releaseBuildId == manifest.toolingReleaseBuildId) {
            "Standalone Bundle build identity is inconsistent"
        }
        check(manifest.jarFileNames.distinct().size == manifest.jarFileNames.size) { "Duplicate standalone runtime JAR" }
        manifest.jarFileNames.forEach { name ->
            validateFileName(name)
            val source = bundle.rootDir.resolve("jars/$name")
            check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Invalid standalone runtime JAR: $name" }
            check(source.sha256() == manifest.jarSha256[name]) { "SHA-256 check failed for $name" }
        }
        check(manifest.bootstrapFileNames.isNotEmpty()) { "Standalone Bundle bootstrap is empty" }
        manifest.bootstrapFileNames.forEach { name ->
            validateFileName(name)
            val source = bundle.rootDir.resolve("bootstrap/$name")
            check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Invalid standalone bootstrap file: $name" }
            check(source.sha256() == manifest.bootstrapSha256[name]) { "SHA-256 check failed for $name" }
        }
    }

    private fun validateInstalledManifest(manifest: StandaloneRuntimeManifest) {
        manifest.jarFileNames.forEach { name ->
            validateFileName(name)
            val jar = storageDir.resolve(name)
            check(jar.isFile && jar.sha256() == manifest.jarSha256[name]) { "Installed standalone runtime is invalid: $name" }
        }
    }

    private fun publishImmutable(source: File, target: File, hash: String) {
        if (target.exists()) {
            check(target.isFile && !Files.isSymbolicLink(target.toPath()) && target.sha256() == hash) {
                "Immutable standalone JAR conflicts with existing file: ${target.name}"
            }
            return
        }
        val temp = target.parentFile.resolve("${target.name}.${System.nanoTime()}.tmp")
        try {
            source.copyTo(temp)
            check(temp.sha256() == hash) { "Copied standalone JAR failed verification: ${target.name}" }
            moveAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun installLaunchers(manifest: StandaloneRuntimeManifest) {
        binDir.mkdirs()
        val bootstrapDir = juggRootDir.resolve("standalone/bootstrap/${manifest.toolingReleaseBuildId}")
        val javaMain = "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap"
        val posix = "#!/bin/sh\nset -eu\nexec java -cp \"${bootstrapDir.absolutePath}/*\" $javaMain \"\$@\"\n"
        val windows = "@echo off\r\njava -cp \"${bootstrapDir.absolutePath}\\*\" $javaMain %*\r\n"
        binDir.resolve("jugg-standalone").apply { writeText(posix); setExecutable(true, false) }
        binDir.resolve("jugg-standalone.cmd").writeText(windows)
        binDir.resolve("jugg-standalone.bat").writeText(windows)
    }

    private fun publishTooling(bundle: StandaloneBundle) {
        val bootstrapDir = juggRootDir.resolve("standalone/bootstrap/${bundle.manifest.toolingReleaseBuildId}")
        bootstrapDir.mkdirs()
        bundle.manifest.bootstrapFileNames.forEach { fileName ->
            publishImmutable(bundle.rootDir.resolve("bootstrap/$fileName"), bootstrapDir.resolve(fileName),
                bundle.manifest.bootstrapSha256.getValue(fileName))
        }
    }

    private fun installPythonCli(sourceDir: File) {
        check(sourceDir.resolve("jugg.py").isFile) { "Standalone Bundle Python CLI is missing" }
        val cliDir = juggRootDir.resolve("bin")
        val stageDir = juggRootDir.resolve("standalone/stage-cli-${System.nanoTime()}")
        try {
            sourceDir.copyRecursively(stageDir)
            cliDir.deleteRecursively()
            moveAtomically(stageDir, cliDir)
            cliDir.resolve("jugg").apply {
                writeText("#!/bin/sh\nexec python3 \"${cliDir.resolve("jugg.py").absolutePath}\" \"\$@\"\n")
                setExecutable(true, false)
            }
            cliDir.resolve("jugg.cmd").writeText("@echo off\r\npython \"${cliDir.resolve("jugg.py").absolutePath}\" %*\r\n")
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun readManifest(file: File): StandaloneRuntimeManifest? {
        if (!file.isFile) return null
        return runCatching { Gson().fromJson(file.readText(), StandaloneRuntimeManifest::class.java) }.getOrNull()
    }

    private fun writeAtomically(target: File, value: Any) {
        target.parentFile?.mkdirs()
        val temp = target.parentFile.resolve("${target.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(Gson().toJson(value))
            moveAtomically(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> withGlobalLock(action: () -> T): T {
        globalLockFile.parentFile.mkdirs()
        return FileChannel.open(globalLockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun validateFileName(name: String) {
        check(name.isNotBlank() && name == File(name).name && !name.contains('/') && !name.contains('\\') &&
                !name.contains("..") && name.none(Char::isISOControl)) { "Invalid standalone file name: $name" }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
