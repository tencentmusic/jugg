package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.sickworm.intellij.jugg.compiler.copyResource
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.nio.file.Files
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo

class WindowsRsyncRuntimeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `discovered keys preserve password fallback and keep credentials out of argv`() {
        val bin = folder.newFolder("authentication tools")
        listOf("rsync", "ssh", "cygpath").forEach { File(bin, "$it.exe").writeText("fixture") }
        val runtime = org.mockito.Mockito.spy(WindowsRsyncRuntime.fromPath(bin.path,
            org.mockito.Mockito.mock(CmdExecutor::class.java)))
        org.mockito.Mockito.doAnswer { it.getArgument<String>(0).replace('\\', '/') }
            .`when`(runtime).localPath(org.mockito.kotlin.any())
        val key = folder.newFile("unrelated-key")
        val password = "fallback p@ss 中文"
        val command = RsyncFetchOutputCommand(password, 22, listOf(key.path),
            "user@host:/remote/app.apk", folder.root.path)

        val arguments = runtime.arguments(command)
        assertTrue(arguments[2].contains("PubkeyAuthentication=yes"))
        assertTrue(arguments[2].contains("PreferredAuthentications=publickey,password,keyboard-interactive"))
        assertTrue(arguments[2].contains("BatchMode=no"))
        assertFalse(arguments.any { it.contains(password) })
        assertEquals(password, runtime.environment(command)["JUGG_RSYNC_PASSWORD"])

        val keyOnly = RsyncFetchOutputCommand(null, 22, listOf(key.path),
            "user@host:/remote/app.apk", folder.root.path)
        assertTrue(runtime.arguments(keyOnly)[2].contains("BatchMode=yes"))
        assertFalse(runtime.environment(keyOnly).containsKey("JUGG_RSYNC_PASSWORD"))
        val passwordOnly = RsyncFetchOutputCommand(password, 22, emptyList(),
            "user@host:/remote/app.apk", folder.root.path)
        assertTrue(runtime.arguments(passwordOnly)[2].contains("PubkeyAuthentication=no"))
        assertTrue(runtime.arguments(passwordOnly)[2].contains("PreferredAuthentications=password,keyboard-interactive"))
    }

    private fun load(root: File) = WindowsRsyncRuntime.fromPath(File(root, "bin").path,
        CmdExecutor(org.mockito.Mockito.mock(com.intellij.openapi.diagnostic.Logger::class.java)))

    private fun runtime(): File = File(System.getenv("JUGG_CYGWIN_HOME") ?: "D:/cygwin64").also {
        assumeTrue(File(it, "bin/rsync.exe").isFile)
    }

    @Test
    fun `probe is best effort when a selected tool fails`() {
        val bin = folder.newFolder("broken tools")
        listOf("rsync", "ssh", "cygpath").forEach { File(bin, "$it.exe").writeText("fixture") }
        val executor = org.mockito.Mockito.mock(CmdExecutor::class.java)
        org.mockito.kotlin.whenever(executor.invokeProcess(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())).thenReturn(127)
        assertFalse(WindowsRsyncRuntime.fromPath(bin.path, executor).probe())
    }

    @Test
    fun `probe runtime failure does not block remaining diagnostics or transfer`() {
        val bin = folder.newFolder("probe failure")
        listOf("rsync", "ssh", "cygpath").forEach { File(bin, "$it.exe").writeText("fixture") }
        val executor = org.mockito.Mockito.mock(CmdExecutor::class.java)
        org.mockito.kotlin.whenever(executor.invokeProcess(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())).thenAnswer { throw IllegalStateException("diagnostic failure") }
            .thenReturn(0, 23)
        val runtime = WindowsRsyncRuntime.fromPath(bin.path, executor)
        assertFalse(runtime.probe())
        assertEquals(23, runtime.invoke(listOf(File(bin, "rsync.exe").path), runtime.environment))
    }

    @Test
    fun `canceled probe never starts another tool`() {
        val bin = folder.newFolder("canceled probe")
        listOf("rsync", "ssh", "cygpath").forEach { File(bin, "$it.exe").writeText("fixture") }
        val executor = org.mockito.Mockito.mock(CmdExecutor::class.java)
        org.mockito.kotlin.whenever(executor.invokeProcess(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())).thenReturn(IGradleCompileClient.Error.ERROR_CANCELED)
        assertFalse(WindowsRsyncRuntime.fromPath(bin.path, executor).probe())
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.times(1)).invokeProcess(
            org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())
    }

    @Test
    fun `interrupted probe preserves interruption instead of continuing`() {
        val bin = folder.newFolder("interrupted probe")
        listOf("rsync", "ssh", "cygpath").forEach { File(bin, "$it.exe").writeText("fixture") }
        val executor = org.mockito.Mockito.mock(CmdExecutor::class.java)
        org.mockito.kotlin.whenever(executor.invokeProcess(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())).thenAnswer { throw InterruptedException("canceled") }
        try {
            assertThrows(InterruptedException::class.java) { WindowsRsyncRuntime.fromPath(bin.path, executor).probe() }
            assertTrue(Thread.currentThread().isInterrupted)
            org.mockito.Mockito.verify(executor, org.mockito.Mockito.times(1)).invokeProcess(
                org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `PATH chooses the first rsync and rejects its incomplete siblings`() {
        val first = folder.newFolder("first 中文 space")
        val second = folder.newFolder("second")
        File(first, "rsync.exe").writeText("fixture")
        listOf("rsync", "ssh", "cygpath").forEach { File(second, "$it.exe").writeText("fixture") }
        val executor = org.mockito.Mockito.mock(CmdExecutor::class.java)
        val failure = assertThrows(WindowsRsyncSetupException::class.java) {
            WindowsRsyncRuntime.fromPath(first.path + File.pathSeparator + second.path, executor)
        }
        assertEquals(File(first, "rsync.exe").absolutePath, failure.toolPath)
        assertEquals(listOf("ssh.exe", "cygpath.exe"), failure.missingTools)
        File(first, "ssh.exe").writeText("fixture")
        val missingCygpath = assertThrows(WindowsRsyncSetupException::class.java) {
            WindowsRsyncRuntime.fromPath(first.path + File.pathSeparator + second.path, executor)
        }
        assertEquals(listOf("cygpath.exe"), missingCygpath.missingTools)
        assertTrue(File(first, "ssh.exe").delete())
        File(first, "cygpath.exe").writeText("fixture")
        val missingSsh = assertThrows(WindowsRsyncSetupException::class.java) {
            WindowsRsyncRuntime.fromPath(first.path + File.pathSeparator + second.path, executor)
        }
        assertEquals(listOf("ssh.exe"), missingSsh.missingTools)
        File(first, "ssh.exe").writeText("fixture")
        assertTrue(WindowsRsyncRuntime.fromPath("\"${first.path}\"" + File.pathSeparator + second.path, executor).probe())
        org.mockito.Mockito.verify(executor).invokeProcess(
            org.mockito.kotlin.eq(listOf(File(first, "rsync.exe").absolutePath, "--version")),
            org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any())
    }

    @Test
    fun `empty and relative PATH entries never search the working directory`() {
        val executor = org.mockito.Mockito.mock(CmdExecutor::class.java)
        assertThrows(WindowsRsyncSetupException::class.java) {
            WindowsRsyncRuntime.fromPath(";.;relative", executor)
        }
    }

    @Test
    fun `installed Cygwin classpath links recover after their targets arrive`() {
        assumeTrue(isWindows)
        val cygwin = File(System.getenv("JUGG_CYGWIN_HOME") ?: "D:/cygwin64")
        assumeTrue(File(cygwin, "bin/rsync.exe").isFile)
        val executor = CmdExecutor(org.mockito.Mockito.mock(com.intellij.openapi.diagnostic.Logger::class.java))
        val runtime = WindowsRsyncRuntime.fromPath(File(cygwin, "bin").path, executor)
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
    fun `missing Cygwin tools never start a transfer`() {
        assertThrows(WindowsRsyncSetupException::class.java) { load(folder.newFolder("missing")) }
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
        assertEquals(load(root).localPath(root.path) + "/", arguments[arguments.lastIndex - 1])
        assertTrue(arguments[2].contains("-p' '2222"))
        assertTrue(arguments[2].contains("PubkeyAuthentication=yes"))
        assertTrue(arguments[2].contains("PreferredAuthentications=publickey"))
        assertTrue(arguments[2].contains("'-F' '/dev/null'"))
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
        assertTrue(arguments[2].contains("/bin/ssh.exe"))
        val environment = runtime.environment(command)
        assertEquals(password, environment["JUGG_RSYNC_PASSWORD"])
        assertEquals("force", environment["SSH_ASKPASS_REQUIRE"])
        val process = ProcessBuilder(copyResource("/tools/windows/rsync/askpass.exe").absolutePath).apply {
            environment().putAll(environment)
        }.start()
        assertEquals(password + "\n", process.inputStream.readBytes().toString(Charsets.UTF_8))
        assertEquals(0, process.waitFor())
    }
}
