package com.sickworm.intellij.jugg.aapt2

import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.JuggGlobalPathManager
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmbeddedAapt2Test {
    @Test
    fun `refreshes cached executable once and keeps a stable process name`() {
        val home = Files.createTempDirectory("jugg-aapt2-test").toFile()
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }
            .map { File(it.toURI()).path }
            .plus(System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct().joinToString(File.pathSeparator)
        val output = File(home, "output.log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-Duser.home=${home.path}", "-cp", classpath,
            javaClass.name).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Isolated aapt2 preparation timed out")
            assertEquals(0, process.exitValue(), output.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            home.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val resource = when {
                isMac -> "/tools/darwin/aapt2-inclink"
                isWindows -> "/tools/windows/aapt2-inclink.exe"
                else -> "/tools/linux/aapt2-inclink"
            }
            val target = JuggGlobalPathManager.resourceFile(resource)
            target.parentFile.mkdirs()
            target.writeText("stale executable")
            val executable = Aapt2DaemonInvoker.getEmbeddedAapt2()
            assertEquals(resource.substringAfterLast('/'), executable.name)
            val bundled = EmbeddedAapt2Test::class.java.getResourceAsStream(resource)!!.use { it.readBytes() }
            assertContentEquals(bundled, executable.readBytes())
            val timestamp = FileTime.fromMillis(1234567890000)
            Files.setLastModifiedTime(executable.toPath(), timestamp)
            assertSame(executable, Aapt2DaemonInvoker.getEmbeddedAapt2())
            assertEquals(timestamp, Files.getLastModifiedTime(executable.toPath()))
        }
    }
}
