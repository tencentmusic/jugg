package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.copyResource
import java.io.File
import java.nio.file.Files

/** Uses an installed Cygwin toolchain without distributing or installing it. */
internal class WindowsRsyncRuntime private constructor(
    private val root: File,
    private val executor: CmdExecutor,
) {
    val environment: Map<String, String> = mapOf("CYGWIN" to "winsymlinks:nativestrict")

    init {
        val missing = listOf("ssh", "cygpath").map { "$it.exe" }.filterNot { File(root, it).isFile }
        if (missing.isNotEmpty()) throw WindowsRsyncSetupException("missing_tools", executable("rsync"), missing)
    }

    private fun executable(name: String): String = File(root, "$name.exe").absolutePath

    private fun startFailureReason(exception: java.io.IOException): String =
        exception.javaClass.simpleName + (Regex("error=\\d+").find(exception.message.orEmpty())?.value?.let { ": $it" } ?: "")

    /** Best-effort startup checks; the real transfer remains the authoritative check. */
    fun probe(): Boolean = probeWindowsRsync(root, executor, environment)

    fun invoke(arguments: List<String>, environment: Map<String, String>, output: MutableList<String>? = null): Int {
        val result = try {
            executor.invokeProcess(arguments, environment, output)
        } catch (exception: java.io.IOException) {
            throw WindowsRsyncSetupException("tool_start_failed", executable("rsync"), safeReason = startFailureReason(exception))
        }
        if (result < -65536) {
            throw WindowsRsyncSetupException("tool_start_failed", executable("rsync"), safeReason = "Exit code: $result")
        }
        return result
    }

    fun canRetryLinkCreation(command: RsyncCommand, result: Int, output: List<String>): Boolean =
        command is RsyncFetchClasspathCommand && result == 23 && output.any {
            it.contains("symlink ") && it.contains("failed: No such file or directory")
        }

    fun localPath(path: String): String {
        val file = File(path).absoluteFile
        require(!file.path.startsWith("\\\\")) { "Windows rsync does not support UNC paths" }
        require(Files.getFileStore(file.toPath().let { if (Files.exists(it)) it else file.parentFile.toPath() })
            .type().equals("NTFS", ignoreCase = true)) { "Windows rsync requires local NTFS storage" }
        val output = mutableListOf<String>()
        val result = try {
            executor.invokeProcess(listOf(executable("cygpath"), "-u", file.path), environment, output, quietOnSuccess = true)
        } catch (exception: java.io.IOException) {
            throw WindowsRsyncSetupException("tool_start_failed", executable("cygpath"), safeReason = startFailureReason(exception))
        }
        if (result != 0 || output.size != 1 || !output.single().startsWith('/')) {
            throw WindowsRsyncSetupException("path_conversion_failed", executable("cygpath"), safeReason = "Exit code: $result; invalid conversion result")
        }
        return output.single()
    }

    fun arguments(command: RsyncCommand): List<String> {
        val password = authenticationPassword(command)
        val ssh = mutableListOf(localPath(executable("ssh")),
            "-F", "/dev/null",
            "-o", "PubkeyAuthentication=${if (password == null) "yes" else "no"}",
            "-o", "PreferredAuthentications=${if (password == null) "publickey" else "password,keyboard-interactive"}",
            "-o", "NumberOfPasswordPrompts=1",
            "-p", command.remoteSshPort.toString(), "-o", "BatchMode=${if (password == null) "yes" else "no"}", "-o", "IdentitiesOnly=yes",
            "-o", "IdentityAgent=none", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=30")
        val knownHosts = File(System.getProperty("user.home"), ".ssh/known_hosts")
        Files.createDirectories(knownHosts.parentFile.toPath())
        ssh += listOf("-o", "UserKnownHostsFile=${localPath(knownHosts.path)}")
        val keys = command.keyPathList.ifEmpty {
            listOfNotNull(command.password?.let { File(it) }?.takeIf { it.isFile && it.isAbsolute }?.path)
        }
        require(keys.isNotEmpty() || password != null) { "Windows rsync requires an SSH key or password" }
        keys.forEach { ssh += listOf("-i", localPath(it)) }
        return listOf(executable("rsync"), "-e", ssh.joinToString(" ", transform = ::quote)) + command.windowsArguments(this)
    }

    fun environment(command: RsyncCommand): Map<String, String> {
        val password = authenticationPassword(command) ?: return environment
        require(!password.contains('\n') && !password.contains('\r')) { "SSH password must be a single line" }
        val askpass = copyResource("/tools/windows/rsync/askpass.exe")
        return environment + mapOf("SSH_ASKPASS" to localPath(askpass.path), "SSH_ASKPASS_REQUIRE" to "force",
            "DISPLAY" to "jugg:0", "JUGG_RSYNC_PASSWORD" to password)
    }

    private fun authenticationPassword(command: RsyncCommand): String? = command.password
        ?.takeIf { it.isNotEmpty() && command.keyPathList.isEmpty() && !(File(it).isAbsolute && File(it).isFile) }

    companion object {
        fun load(executor: CmdExecutor): WindowsRsyncRuntime {
            require(System.getProperty("os.arch").lowercase() in listOf("amd64", "x86_64")) {
                "Windows rsync supports x64 only"
            }
            return fromPath(System.getenv("PATH").orEmpty(), executor)
        }

        internal fun fromPath(path: String, executor: CmdExecutor): WindowsRsyncRuntime {
            val rsync = path.split(File.pathSeparator).asSequence().map { it.trim().trim('"') }
                .filter { it.isNotEmpty() }.map { File(it) }.filter { it.isAbsolute }
                .map { File(it, "rsync.exe") }.firstOrNull { it.isFile }
                ?: throw WindowsRsyncSetupException("rsync_not_found")
            return WindowsRsyncRuntime(rsync.absoluteFile.parentFile, executor)
        }

        private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}
