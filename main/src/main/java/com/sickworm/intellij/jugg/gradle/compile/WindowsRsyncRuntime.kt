package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Uses a selected acceptance package or an installed Cygwin toolchain. */
internal class WindowsRsyncRuntime private constructor(
    private val root: File,
    private val executor: CmdExecutor,
    private val manifest: JsonObject,
    verifyHashes: Boolean,
) {
    constructor(root: File, executor: CmdExecutor) : this(root, executor,
        Gson().fromJson(File(root, "runtime.json").readText(), JsonObject::class.java), true)
    private val isCygwin = manifest.get("engine").asString == "cygwin"
    val environment: Map<String, String> = if (isCygwin) mapOf("CYGWIN" to "winsymlinks:nativestrict") else emptyMap()

    init {
        require(manifest.get("engine").asString in listOf("oc-rsync", "cygwin"))
        val hashes = manifest.getAsJsonObject("sha256") ?: JsonObject()
        require(!verifyHashes || hashes.size() > 0) { "Windows rsync runtime has no file hashes" }
        hashes.entrySet().forEach { (name, hash) ->
            val file = containedFile(name)
            require(file.isFile && sha256(file) == hash.asString) { "Invalid runtime file: $name" }
        }
        listOf("rsync", "ssh").plus(if (isCygwin) listOf("cygpath") else emptyList()).forEach { name ->
            val relative = manifest.get(name).asString
            require(containedFile(relative).isFile) { "Missing runtime executable: $name" }
            require(!verifyHashes || hashes.has(relative)) { "Unverified runtime executable: $name" }
        }
    }

    private fun containedFile(name: String): File {
        val file = File(root, name).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "Runtime path escapes package: $name" }
        return file
    }

    private fun executable(name: String): String = containedFile(manifest.get(name).asString).absolutePath

    fun probe(): Boolean = executor.invokeProcess(listOf(executable("rsync"), "--version"), environment) == 0

    fun canRetryLinkCreation(command: RsyncCommand, result: Int, output: List<String>): Boolean =
        isCygwin && command is RsyncFetchClasspathCommand && result == 23 && output.any {
            it.contains("symlink ") && it.contains("failed: No such file or directory")
        }

    fun localPath(path: String): String {
        val file = File(path).absoluteFile
        require(!file.path.startsWith("\\\\")) { "Windows rsync does not support UNC paths" }
        require(Files.getFileStore(file.toPath().let { if (Files.exists(it)) it else file.parentFile.toPath() })
            .type().equals("NTFS", ignoreCase = true)) { "Windows rsync requires local NTFS storage" }
        if (!isCygwin) return file.path.replace('\\', '/')
        val output = mutableListOf<String>()
        val result = executor.invokeProcess(listOf(executable("cygpath"), "-u", file.path), environment, output)
        require(result == 0 && output.size == 1 && output.single().startsWith('/')) { "cygpath failed: $result $output" }
        return output.single()
    }

    fun arguments(command: RsyncCommand): List<String> {
        val ssh = mutableListOf(if (isCygwin) localPath(executable("ssh")) else executable("ssh").replace('\\', '/'),
            "-F", if (isCygwin) "/dev/null" else "NUL",
            "-o", "PubkeyAuthentication=yes", "-o", "PreferredAuthentications=publickey",
            "-p", command.remoteSshPort.toString(), "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "IdentityAgent=none", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=30")
        val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts")
        Files.createDirectories(knownHosts.parentFile.toPath())
        ssh += listOf("-o", "UserKnownHostsFile=${localPath(knownHosts.path)}")
        val keys = command.keyPathList.ifEmpty {
            listOfNotNull(command.password?.let { File(it) }?.takeIf { it.isFile && it.isAbsolute }?.path)
        }
        require(keys.isNotEmpty()) { "Windows rsync requires non-interactive SSH private keys" }
        require(command.password.isNullOrEmpty() || File(command.password).isFile) { "Windows rsync does not support password authentication" }
        keys.forEach { ssh += listOf("-i", localPath(it)) }
        return listOf(executable("rsync"), "-e", ssh.joinToString(" ", transform = ::quote)) + command.windowsArguments(this)
    }

    companion object {
        @Synchronized
        fun load(executor: CmdExecutor, logger: com.intellij.openapi.diagnostic.Logger): WindowsRsyncRuntime {
            require(System.getProperty("os.arch").lowercase() in listOf("amd64", "x86_64")) {
                "Windows rsync supports x64 only"
            }
            val override = System.getenv("JUGG_WINDOWS_RSYNC_RUNTIME")
            if (!override.isNullOrBlank()) return WindowsRsyncRuntime(File(override), executor)
            val cygwin = System.getenv("JUGG_CYGWIN_HOME")
            if (!cygwin.isNullOrBlank()) return installedCygwin(File(cygwin), executor)
            val resource = "/tools/windows/rsync/runtime.zip"
            val url = WindowsRsyncRuntime::class.java.getResource(resource)
                ?: return detectInstalledCygwin(executor)
            val id = url.openStream().use { stream ->
                MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") { "%02x".format(it) }
            }
            val root = JuggGlobalPathManager.resourceFile("/tools/windows/rsync/$id")
            if (File(root, "runtime.json").isFile) {
                try {
                    return WindowsRsyncRuntime(root, executor)
                } catch (exception: RuntimeException) {
                    logger.warn("Windows rsync cache validation failed; restoring bundled runtime once", exception)
                } catch (exception: java.io.IOException) {
                    logger.warn("Windows rsync cache read failed; restoring bundled runtime once", exception)
                }
            }
            ZipInputStream(url.openStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) extractFile(root, entry.name, zip)
                }
            }
            return WindowsRsyncRuntime(root, executor)
        }

        private fun detectInstalledCygwin(executor: CmdExecutor): WindowsRsyncRuntime {
            val roots = listOf(File("C:/cygwin64"), File("D:/cygwin64")) +
                    System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it).parentFile }
            val root = roots.filterNotNull().firstOrNull { directory ->
                listOf("rsync", "ssh", "cygpath").all { File(directory, "bin/$it.exe").isFile }
            }
                ?: error("Install Cygwin x64 with rsync and openssh packages; set JUGG_CYGWIN_HOME to its installation directory")
            return installedCygwin(root, executor)
        }

        internal fun installedCygwin(root: File, executor: CmdExecutor): WindowsRsyncRuntime {
            val manifest = JsonObject().apply {
                addProperty("engine", "cygwin")
                listOf("rsync", "ssh", "cygpath").forEach { addProperty(it, "bin/$it.exe") }
            }
            return WindowsRsyncRuntime(root, executor, manifest, false)
        }

        private fun extractFile(root: File, name: String, zip: ZipInputStream) {
            val file = File(root, name).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe runtime ZIP entry" }
            Files.createDirectories(file.parentFile.toPath())
            file.outputStream().use { zip.copyTo(it) }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}
