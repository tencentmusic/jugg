package com.sickworm.intellij.jugg.cmdline.standalone

import com.google.gson.Gson
import com.sickworm.intellij.jugg.ai.skills.PythonRuntimeResolver
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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
    private val releasesDir = juggRootDir.resolve("standalone/releases")
    private val globalLockFile = juggRootDir.resolve("locks/global.lock")

    fun install(bundleDir: File, managedBy: String = "external") {
        validateEnvironment()
        val bundle = StandaloneBundle.read(bundleDir)
        val manifest = bundle.manifest.copy(managedBy = managedBy)
        installValidated(StandaloneBundle(bundle.rootDir, bundle.manifestFile, manifest))
    }

    internal fun installValidated(bundle: StandaloneBundle) {
        withGlobalLock {
            validateBundle(bundle)
            storageDir.mkdirs()
            bundle.manifest.jarFileNames.forEach { jarName ->
                publishImmutable(bundle.rootDir.resolve("jars/$jarName"), storageDir.resolve(jarName),
                    bundle.manifest.jarSha256.getValue(jarName))
            }
            publishTooling(bundle)
            val releaseDir = releasesDir.resolve(bundle.manifest.releaseBuildId)
            releaseDir.mkdirs()
            writeAtomically(releaseDir.resolve("standalone_load_manifest.json"), bundle.manifest)
            installPythonCli(bundle.rootDir.resolve("cli"))
            installLaunchers(bundle.manifest)
            writeAtomically(activeManifestFile, bundle.manifest)
        }
        stopRunningDaemons()
        withGlobalLock { deleteLegacyRuntimeResources() }
    }

    fun readActiveManifest(): StandaloneRuntimeManifest? = readManifest(activeManifestFile)

    private fun validateEnvironment() {
        check(Runtime.version().feature() >= 11 && ToolProvider.getSystemJavaCompiler() != null) {
            "A complete JDK 11+ is required to install Jugg standalone"
        }
        PythonRuntimeResolver.requireCommand()
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
        val posix = """
            #!/bin/sh
            set -eu
            JUGG_NOFILE_TARGET=65536
            JUGG_NOFILE_SOFT="${'$'}(ulimit -Sn)"
            JUGG_NOFILE_HARD="${'$'}(ulimit -Hn)"
            if [ "${'$'}JUGG_NOFILE_SOFT" != "unlimited" ] && [ "${'$'}JUGG_NOFILE_SOFT" -lt "${'$'}JUGG_NOFILE_TARGET" ]; then
              if [ "${'$'}JUGG_NOFILE_HARD" != "unlimited" ] && [ "${'$'}JUGG_NOFILE_HARD" -lt "${'$'}JUGG_NOFILE_TARGET" ]; then
                JUGG_NOFILE_TARGET="${'$'}JUGG_NOFILE_HARD"
              fi
              ulimit -Sn "${'$'}JUGG_NOFILE_TARGET" 2>/dev/null || true
            fi
            echo "Jugg standalone max open files: ${'$'}(ulimit -Sn)"
            if [ -n "${'$'}{JAVA_HOME:-}" ] && [ -x "${'$'}JAVA_HOME/bin/java" ]; then
              JAVA_CMD="${'$'}JAVA_HOME/bin/java"
            elif command -v java >/dev/null 2>&1; then
              JAVA_CMD=java
            else
              echo "jugg-standalone: Java was not found. Set JAVA_HOME or add java to PATH." >&2
              exit 127
            fi
            exec "${'$'}JAVA_CMD" -cp "${bootstrapDir.absolutePath}/*" $javaMain "${'$'}@"
        """.trimIndent() + "\n"
        val windows = """
            @echo off
            setlocal
            if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
              set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
              goto run
            )
            where java >nul 2>nul
            if errorlevel 1 (
              echo jugg-standalone: Java was not found. Set JAVA_HOME or add java to PATH. 1>&2
              exit /b 127
            )
            set "JAVA_EXE=java"
            :run
            "%JAVA_EXE%" -cp "${bootstrapDir.absolutePath}\*" $javaMain %*
            exit /b %ERRORLEVEL%
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
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
        check(sourceDir.resolve("jugg").isFile && sourceDir.resolve("jugg.cmd").isFile) {
            "Standalone Bundle Python launcher is missing"
        }
        val cliDir = juggRootDir.resolve("bin")
        val stageDir = juggRootDir.resolve("standalone/stage-cli-${System.nanoTime()}")
        try {
            sourceDir.copyRecursively(stageDir)
            cliDir.deleteRecursively()
            moveAtomically(stageDir, cliDir)
            cliDir.resolve("jugg").setExecutable(true, false)
            val pythonCli = cliDir.resolve("jugg.py")
            check(pythonCli.setExecutable(true, false) || pythonCli.canExecute()) {
                "Failed to make standalone Python CLI executable: $pythonCli"
            }
            cliDir.resolve("jugg.cmd").apply {
                writeText(readText().replace("\r\n", "\n").replace("\n", "\r\n"), Charsets.US_ASCII)
            }
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun stopRunningDaemons() {
        val currentPid = ProcessHandle.current().pid()
        val rootPath = juggRootDir.canonicalFile.path
        val daemons = ProcessHandle.allProcesses().use { processes ->
            processes.iterator().asSequence().filter { process ->
                process.pid() != currentPid && isStandaloneDaemon(process, rootPath)
            }.toList()
        }
        daemons.forEach(ProcessHandle::destroyForcibly)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (daemons.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) Thread.sleep(20)
        check(daemons.none(ProcessHandle::isAlive)) {
            "Failed to stop standalone daemon processes: ${daemons.filter(ProcessHandle::isAlive).map(ProcessHandle::pid)}"
        }
    }

    /** Deletes the obsolete versioned deployer directory without following symbolic links. */
    private fun deleteLegacyRuntimeResources() {
        val runtimeDir = juggRootDir.resolve("runtime").toPath()
        if (!Files.exists(runtimeDir, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(runtimeDir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exception: IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun isStandaloneDaemon(process: ProcessHandle, rootPath: String): Boolean {
        val arguments = process.info().arguments().orElse(emptyArray())
        if (STANDALONE_BOOTSTRAP_MAIN !in arguments) return false
        val processRoot = arguments.firstOrNull { it.startsWith(JUGG_ROOT_ARGUMENT) }
            ?.substringAfter('=') ?: File(System.getProperty("user.home"), ".jugg").path
        return runCatching { File(processRoot).canonicalPath == rootPath }.getOrDefault(false)
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

    private companion object {
        const val STANDALONE_BOOTSTRAP_MAIN = "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap"
        const val JUGG_ROOT_ARGUMENT = "-Djugg.root.dir="
    }
}
