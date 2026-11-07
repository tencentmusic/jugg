package com.sickworm.intellij.jugg.project.runtime

import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JuggProcessLeaseTest {
    @Test fun liveProcessLocksSurviveIdleAndReleaseOnProcessExit() {
        val home = Files.createTempDirectory("jugg-lease-中文").toFile()
        val root = File(home, "root")
        val classpath = listOf(JuggProcessLeaseTest::class.java, JuggProcessLease::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, JuggProcessLeaseTest::class.java.name, root.path, home.path).redirectErrorStream(true).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            assertEquals(setOf(process.pid()), JuggProcessLease.liveProcesses("idea", root, home))
            JuggProcessLease.acquire("idea", root, home).use {
                assertEquals(2, JuggProcessLease.liveProcesses("idea", root, home).size)
                assertFailsWith<IllegalStateException> {
                    JuggGlobalDirectoryMigration(root, home).prepare(home.resolve("new"))
                }
                assertFalse(home.resolve(".juggdir").exists())
            }
            assertTrue(JuggProcessLease.liveProcesses("standalone", root, home).isEmpty())
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertTrue(JuggProcessLease.liveProcesses("idea", root, home).isEmpty())
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            home.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            JuggProcessLease.acquire("idea", File(args[0]), File(args[1])).use {
                println("ready")
                System.`in`.read()
            }
        }
    }
}
