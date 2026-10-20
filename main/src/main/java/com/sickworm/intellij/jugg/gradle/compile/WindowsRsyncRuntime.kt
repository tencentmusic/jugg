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
        require(listOf("rsync", "ssh", "cygpath").all { File(root, "bin/$it.exe").isFile }) {
            "Install Cygwin x64 with rsync and openssh packages; set JUGG_CYGWIN_HOME to its installation directory"
        }
    }

    private fun executable(name: String): String = File(root, "bin/$name.exe").absolutePath

    fun probe(): Boolean = executor.invokeProcess(listOf(executable("rsync"), "--version"), environment) == 0

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
        val result = executor.invokeProcess(listOf(executable("cygpath"), "-u", file.path), environment, output)
        require(result == 0 && output.size == 1 && output.single().startsWith('/')) { "cygpath failed: $result $output" }
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
            val home = System.getenv("JUGG_CYGWIN_HOME")
            return if (home.isNullOrBlank()) detectInstalledCygwin(executor) else installedCygwin(File(home), executor)
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
            return WindowsRsyncRuntime(root, executor)
        }

        private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}
