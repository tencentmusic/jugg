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
        assertTrue(arguments.indexOf("--include=/build/jugg/config/**") < arguments.indexOf("--exclude=*.iml"))
        assertEquals("user@host:/remote/project", arguments.last())
        assertEquals(root.path.replace('\\', '/') + "/", arguments[arguments.lastIndex - 1])
        assertTrue(arguments[2].contains("-p' '2222"))
    }
}
