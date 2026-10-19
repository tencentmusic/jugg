package com.sickworm.intellij.jugg.gradle.compile

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo

class WindowsRsyncRuntimeTest {
    @get:Rule val folder = TemporaryFolder()
    private fun load(root: File) = WindowsRsyncRuntime(root,
        CmdExecutor(org.mockito.Mockito.mock(com.intellij.openapi.diagnostic.Logger::class.java)))

    private fun runtime(): File {
        val root = folder.newFolder("runtime space & (test)")
        val executable = File(root, "tool.exe").apply { writeText("fixed tool") }
        val hash = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(root, "runtime.json").writeText(Gson().toJson(mapOf(
            "engine" to "oc-rsync", "rsync" to "tool.exe", "ssh" to "tool.exe",
            "sha256" to mapOf("tool.exe" to hash),
        )))
        return root
    }

    @Test
    fun `installed Cygwin classpath links recover after their targets arrive`() {
        assumeTrue(isWindows)
        val cygwin = File(System.getenv("JUGG_CYGWIN_HOME") ?: "D:/cygwin64")
        assumeTrue(File(cygwin, "bin/rsync.exe").isFile)
        val executor = CmdExecutor(org.mockito.Mockito.mock(com.intellij.openapi.diagnostic.Logger::class.java))
        val runtime = WindowsRsyncRuntime.installedCygwin(cygwin, executor)
        val source = folder.newFolder("source 中文 space")
        val generated = File(source, "app/build/generated").apply { mkdirs() }
        File(generated, "z-target.txt").writeText("native target")
        Files.createSymbolicLink(File(generated, "a-link.txt").toPath(), java.nio.file.Path.of("z-target.txt"))
        val destination = folder.newFolder("destination 中文 space")
        val key = folder.newFile("unused-key")
        val command = RsyncFetchClasspathCommand(null, 22, listOf(key.path), runtime.localPath(source.path),
            destination.path, listOf(ModuleBuildPathInfo(source, File(source, "app"), "debug", buildDirRelativePath = "")))
        val arguments = runtime.arguments(command)
        val output = mutableListOf<String>()
        val first = executor.invokeProcess(arguments, runtime.environment, output)
        assertEquals(23, first)
        assertTrue(runtime.canRetryLinkCreation(command, first, output))
        assertEquals("native target", File(destination, "app/build/generated/z-target.txt").readText())
        assertEquals(0, executor.invokeProcess(arguments, runtime.environment))
        val link = File(destination, "app/build/generated/a-link.txt").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("native target", Files.readString(link))
        assertFalse(runtime.canRetryLinkCreation(command, 255, output))
        assertFalse(runtime.canRetryLinkCreation(command, IGradleCompileClient.Error.ERROR_CANCELED, output))
        Files.createSymbolicLink(File(generated, "dangling.txt").toPath(), java.nio.file.Path.of("absent.txt"))
        repeat(2) { assertEquals(23, executor.invokeProcess(arguments, runtime.environment)) }
        assertFalse(Files.exists(File(destination, "app/build/generated/dangling.txt").toPath(),
            java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `damaged runtime never starts a transfer`() {
        val root = runtime()
        File(root, "tool.exe").writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { load(root) }
    }

    @Test
    fun `missing runtime never starts a transfer`() {
        val root = runtime()
        assertTrue(File(root, "tool.exe").delete())
        assertThrows(IllegalArgumentException::class.java) { load(root) }
    }

    @Test
    fun `upload preserves unquoted filters and remote posix paths`() {
        assumeTrue(isWindows)
        val root = runtime()
        val key = folder.newFile("key space.pem")
        val command = RsyncSyncFileCommand(null, 2222, listOf(key.path), root.path,
            "user@host:/remote/project", "", listOf("local.properties", "*.iml"))
        val arguments = load(root).arguments(command)
        assertTrue(arguments.contains("--exclude=local.properties"))
        assertFalse(arguments.contains("--exclude='local.properties'"))
        assertFalse(arguments.contains("--copy-unsafe-links"))
        assertTrue(arguments.indexOf("--include=/build/jugg/config/**") < arguments.indexOf("--exclude=*.iml"))
        assertEquals("user@host:/remote/project", arguments.last())
        assertEquals(root.path.replace('\\', '/') + "/", arguments[arguments.lastIndex - 1])
        assertTrue(arguments[2].contains("-p' '2222"))
        assertTrue(arguments[2].contains("PubkeyAuthentication=yes"))
        assertTrue(arguments[2].contains("PreferredAuthentications=publickey"))
        assertTrue(arguments[2].contains("'-F' 'NUL'"))
        assertFalse(arguments[2].contains("UserKnownHostsFile=\""))
    }

    @Test
    fun `password never appears in process arguments and disables batch authentication`() {
        assumeTrue(isWindows)
        val root = runtime()
        val password = "fixture p@ss & 中文"
        val command = RsyncSyncFileCommand(password, 22, emptyList(), root.path, "user@host:/remote", "")
        val runtime = load(root)
        val arguments = runtime.arguments(command)
        assertFalse(arguments.any { it.contains(password) })
        assertTrue(arguments[2].contains("BatchMode=no"))
        assertTrue(arguments[2].contains("jugg-ssh.exe"))
        val environment = runtime.environment(command)
        assertEquals(password, environment["JUGG_RSYNC_PASSWORD"])
        assertEquals("force", environment["SSH_ASKPASS_REQUIRE"])
        val process = ProcessBuilder(environment.getValue("SSH_ASKPASS")).apply {
            environment().putAll(environment)
        }.start()
        assertEquals(password + "\n", process.inputStream.readBytes().toString(Charsets.UTF_8))
        assertEquals(0, process.waitFor())
    }
}
