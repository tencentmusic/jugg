package com.sickworm.intellij.jugg.project.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProjectExecutionLockTest {

    @Test
    fun `project lock is reentrant and writes owner metadata`() {
        val projectDir = Files.createTempDirectory("jugg-project-lock").toFile()
        val pathManager = JuggPathManager(projectDir)
        val lockManager = FileExecutionLockManager(
            pathManager = pathManager,
            runtimeIdentity = RuntimeIdentity("idea", "test"),
        )

        lockManager.withProjectLock("outer") {
            assertTrue(pathManager.runtimeLockOwnerFile.exists())
            val metadata = lockManager.readProjectLockOwner()
            assertEquals("idea", metadata?.runtimeType)
            assertEquals("outer", metadata?.command)

            lockManager.withProjectLock("inner") {
                assertEquals("outer", lockManager.readProjectLockOwner()?.command)
            }
        }

        assertFalse(pathManager.runtimeLockOwnerFile.exists())
    }

    @Test
    fun `two managers in one process serialize project writes`() {
        val projectDir = Files.createTempDirectory("jugg-project-lock").toFile()
        val pathManager = JuggPathManager(projectDir)
        val first = FileExecutionLockManager(pathManager, RuntimeIdentity("idea", "test"))
        val second = FileExecutionLockManager(pathManager, RuntimeIdentity("standalone", "test"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        val firstThread = Thread {
            first.withProjectLock("first") {
                maxActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                active.decrementAndGet()
            }
        }
        val secondThread = Thread {
            entered.await(5, TimeUnit.SECONDS)
            second.withProjectLock("second") {
                maxActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                active.decrementAndGet()
            }
        }

        firstThread.start()
        secondThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(1, active.get())
        release.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertEquals(1, maxActive.get())
    }

    @Test
    fun `try project lock returns immediately when another thread owns the lock`() {
        val projectDir = Files.createTempDirectory("jugg-project-try-lock").toFile()
        val first = FileExecutionLockManager(JuggPathManager(projectDir), RuntimeIdentity("idea", "test"))
        val second = FileExecutionLockManager(JuggPathManager(projectDir), RuntimeIdentity("standalone", "test"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tryFinished = CountDownLatch(1)
        var result: String? = "not-run"

        val ownerThread = Thread {
            first.withProjectLock("owner") {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val tryThread = Thread {
            result = second.tryWithProjectLock("status") { "acquired" }
            tryFinished.countDown()
        }
        ownerThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        tryThread.start()
        try {
            assertTrue(tryFinished.await(500, TimeUnit.MILLISECONDS))
            assertNull(result)
        } finally {
            release.countDown()
            ownerThread.join(5_000)
            tryThread.join(5_000)
        }
    }

    @Test
    fun `try project lock remains reentrant for the owner thread`() {
        val projectDir = Files.createTempDirectory("jugg-project-try-reentrant").toFile()
        val lockManager = FileExecutionLockManager(
            JuggPathManager(projectDir),
            RuntimeIdentity("idea", "test"),
        )

        lockManager.withProjectLock("outer") {
            assertEquals("acquired", lockManager.tryWithProjectLock("inner") { "acquired" })
            assertEquals("outer", lockManager.readProjectLockOwner()?.command)
        }
    }

    @Test
    fun `global lock serializes different projects and write types`() {
        val globalRoot = Files.createTempDirectory("jugg-global-lock").toFile()
        val first = FileExecutionLockManager(
            JuggPathManager(Files.createTempDirectory("jugg-global-first").toFile()),
            RuntimeIdentity("idea", "test"),
            globalRoot,
        )
        val second = FileExecutionLockManager(
            JuggPathManager(Files.createTempDirectory("jugg-global-second").toFile()),
            RuntimeIdentity("standalone", "test"),
            globalRoot,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val firstThread = Thread {
            first.withGlobalLock("cli update") {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val secondThread = Thread {
            entered.await(5, TimeUnit.SECONDS)
            second.withGlobalLock("hot update") {
                secondEntered.countDown()
            }
        }
        firstThread.start()
        secondThread.start()

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        firstThread.join(5_000)
        secondThread.join(5_000)
    }

    @Test
    fun `task runner global facade shares task runner instance lock`() {
        val globalRoot = Files.createTempDirectory("jugg-global-direct-lock").toFile()
        val taskLock = FileExecutionLockManager(
            JuggPathManager(Files.createTempDirectory("jugg-global-task").toFile()),
            RuntimeIdentity("idea", "test"),
            globalRoot,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val taskEntered = CountDownLatch(1)

        val directThread = Thread {
            TaskRunnerManager.runGlobalWriteLocked("load hot update", globalRoot) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val taskThread = Thread {
            entered.await(5, TimeUnit.SECONDS)
            taskLock.withGlobalLock("update cli") {
                taskEntered.countDown()
            }
        }
        directThread.start()
        taskThread.start()

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(taskEntered.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(taskEntered.await(5, TimeUnit.SECONDS))
        directThread.join(5_000)
        taskThread.join(5_000)
        assertEquals(File(globalRoot, "locks/global.lock"), JuggGlobalPathManager.globalLockFile(globalRoot))
    }

    @Test
    fun `global lock waits for overlapping JVM file lock from another coordinator`() {
        val globalRoot = Files.createTempDirectory("jugg-global-overlapping-lock").toFile()
        val lockFile = JuggGlobalPathManager.globalLockFile(globalRoot)
        lockFile.parentFile.mkdirs()
        val entered = CountDownLatch(1)

        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                val thread = Thread {
                    TaskRunnerManager.runGlobalWriteLocked("wait for loader", globalRoot) {
                        entered.countDown()
                    }
                }
                thread.start()

                assertFalse(entered.await(200, TimeUnit.MILLISECONDS))
                assertTrue(thread.isAlive)
            }
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `another process acquires project lock after owner is killed`() {
        val projectDir = Files.createTempDirectory("jugg-project-lock-process").toFile()
        val firstReady = File(projectDir, "first.ready")
        val secondReady = File(projectDir, "second.ready")
        val first = startLockProcess(projectDir, firstReady, holdMillis = 30_000)
        assertTrue(waitForFile(firstReady))

        val second = startLockProcess(projectDir, secondReady, holdMillis = 0)
        Thread.sleep(300)
        assertFalse(secondReady.exists())

        first.destroyForcibly()
        first.waitFor(5, TimeUnit.SECONDS)
        assertTrue(waitForFile(secondReady))
        assertTrue(second.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, second.exitValue())
    }

    @Test
    fun `stale owner metadata is cleared after process crash`() {
        val projectDir = Files.createTempDirectory("jugg-project-lock-stale-owner").toFile()
        val ready = File(projectDir, "ready")
        val owner = startLockProcess(projectDir, ready, holdMillis = 30_000)
        assertTrue(waitForFile(ready))

        owner.destroyForcibly()
        assertTrue(owner.waitFor(5, TimeUnit.SECONDS))

        assertNull(
            FileExecutionLockManager(
                JuggPathManager(projectDir),
                RuntimeIdentity("idea", "test"),
            ).readProjectLockOwner(),
        )
    }

    @Test
    fun `another process acquires project lock after owner exits normally`() {
        val projectDir = Files.createTempDirectory("jugg-project-lock-normal-exit").toFile()
        val firstReady = File(projectDir, "first.ready")
        val secondReady = File(projectDir, "second.ready")
        val first = startLockProcess(projectDir, firstReady, holdMillis = 0)

        assertTrue(waitForFile(firstReady))
        assertTrue(first.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, first.exitValue())
        assertFalse(JuggPathManager(projectDir).runtimeLockOwnerFile.exists())

        val second = startLockProcess(projectDir, secondReady, holdMillis = 0)
        assertTrue(waitForFile(secondReady))
        assertTrue(second.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, second.exitValue())
    }

    private fun startLockProcess(projectDir: File, readyFile: File, holdMillis: Long): Process {
        return ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp",
            System.getProperty("java.class.path"),
            ProjectLockProcessMain::class.java.name,
            projectDir.absolutePath,
            readyFile.absolutePath,
            holdMillis.toString(),
        ).redirectErrorStream(true).start()
    }

    private fun waitForFile(file: File): Boolean {
        repeat(100) {
            if (file.exists()) return true
            Thread.sleep(50)
        }
        return false
    }
}

object ProjectLockProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val projectDir = File(args[0])
        val readyFile = File(args[1])
        val holdMillis = args[2].toLong()
        val lockManager = FileExecutionLockManager(
            JuggPathManager(projectDir),
            RuntimeIdentity("standalone", "test"),
        )
        lockManager.withProjectLock("process") {
            readyFile.writeText("ready")
            if (holdMillis > 0L) {
                Thread.sleep(holdMillis)
            }
        }
    }
}
