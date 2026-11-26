package com.sickworm.intellij.jugg.project.runtime

import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JuggProcessLeaseTest {
    @get:org.junit.Rule val isolatedHome = com.sickworm.intellij.jugg.mock.IsolatedHomeRule()
    @Test fun liveProcessLocksSurviveIdleAndReleaseOnProcessExit() {
        val home = Files.createTempDirectory("jugg-lease-中文").toFile()
        val root = File(home, "root")
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-Duser.home=${JuggGlobalPathManager.currentUserHome}", "-cp", classpath, JuggProcessLeaseTest::class.java.name, root.path).redirectErrorStream(true).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            assertEquals(setOf(process.pid()), JuggProcessLease.liveProcesses("idea", root))
            JuggProcessLease.acquire("idea", root).use {
                assertEquals(2, JuggProcessLease.liveProcesses("idea", root).size)
                assertFailsWith<IllegalStateException> {
                    JuggGlobalDirectoryMigration(root, home).prepare(home.resolve("new"))
                }
                assertFalse(home.resolve(".juggdir").exists())
            }
            assertTrue(JuggProcessLease.liveProcesses("standalone", root).isEmpty())
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertTrue(JuggProcessLease.liveProcesses("idea", root).isEmpty())
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            home.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            JuggProcessLease.acquire("idea", File(args[0])).use {
                println("ready")
                System.`in`.read()
            }
        }
    }
}
