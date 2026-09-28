package com.sickworm.intellij.jugg.logger

import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.BlockingQueue
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileLoggerTest {

    companion object {
        private const val LATEST_LOG_NAME = "compile_latest.log"
        private const val LAST_LATEST_LOG_NAME = "compile_latest-1.log"
        private val MAIN_LOG_REGEX = Regex("""compile_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.\d+\.log""")
        private val MAIN_LOG_GENERATION_REGEX = Regex(""".*\.(\d+)\.log$""")
    }

    private val logDir = createTempDirectory("jugg-file-logger-test").toFile()
    private val legacyRoot = createTempDirectory("jugg-file-logger-legacy-test").toFile()
    private val legacyLogDir = legacyRoot.resolve("build/jugg/log")

    @After
    fun tearDown() {
        FileLogger.isCreateLastLogLinkFile = true
        logDir.deleteRecursively()
        legacyRoot.deleteRecursively()
    }

    @Test
    fun `should write to timestamped main log file without lock file`() {
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.logger.info("hello")

            waitUntil { mainLogFiles().singleOrNull()?.readText()?.contains("hello") == true }

            val mainLogFile = mainLogFiles().single()
            assertTrue(mainLogFile.readText().contains("hello"))

            val lockFiles = logDir.listFiles()
                ?.filter { it.name.endsWith(".lck") }
                .orEmpty()
            assertTrue(lockFiles.isEmpty(), "Unexpected lock files: $lockFiles")

            val latestLogFile = latestLogFile()
            if (latestLogFile.exists()) {
                assertTrue(latestLogFile.readText().contains("hello"))
            }
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `reset should create a new timestamped main log and preserve previous one`() {
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.logger.info("before reset")
            waitUntil { mainLogFiles().singleOrNull()?.readText()?.contains("before reset") == true }
            val firstMainLogFile = mainLogFiles().single()

            Thread.sleep(1100)
            fileLogger.resetLatestCompileLog()
            fileLogger.logger.info("after reset")
            waitUntil { mainLogFiles().size == 2 && mainLogFiles().any { it.readText().contains("after reset") } }

            val mainLogs = mainLogFiles().sortedBy { it.name }
            assertEquals(2, mainLogs.size)
            assertTrue(firstMainLogFile.readText().contains("before reset"))

            val secondMainLogFile = mainLogs.first { it != firstMainLogFile }
            assertTrue(secondMainLogFile.readText().contains("after reset"))
            assertFalse(secondMainLogFile.readText().contains("before reset"))

            val previousLatestLogFile = lastLatestLogFile()
            if (previousLatestLogFile.exists()) {
                assertTrue(previousLatestLogFile.readText().contains("before reset"))
            }
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `new logger instance should preserve previous latest log`() {
        FileLogger(logDir).also { firstLogger ->
            firstLogger.logger.info("before restart")
            waitUntil { latestLogFile().readText().contains("before restart") }
            firstLogger.dispose()
        }

        Thread.sleep(1100)
        FileLogger(logDir).also { secondLogger ->
            try {
                secondLogger.logger.info("after restart")
                waitUntil { latestLogFile().readText().contains("after restart") }

                assertTrue(lastLatestLogFile().readText().contains("before restart"))
            } finally {
                secondLogger.dispose()
            }
        }
    }

    @Test
    fun `reset should not create last latest link when disabled`() {
        FileLogger.isCreateLastLogLinkFile = false
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.logger.info("before reset")
            waitUntil { mainLogFiles().singleOrNull()?.readText()?.contains("before reset") == true }

            Thread.sleep(1100)
            fileLogger.resetLatestCompileLog()
            fileLogger.logger.info("after reset")
            waitUntil { mainLogFiles().size == 2 && mainLogFiles().any { it.readText().contains("after reset") } }

            assertFalse(lastLatestLogFile().exists())
            assertTrue(latestLogFile().exists())
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `recreate should restore main log after log directory deletion`() {
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.logger.info("before delete")
            logDir.deleteRecursively()

            fileLogger.recreateIfDeleted()
            fileLogger.logger.info("after recreate")

            waitUntil { mainLogFiles().singleOrNull()?.readText()?.contains("after recreate") == true }

            val mainLogFile = mainLogFiles().single()
            assertTrue(mainLogFile.readText().contains("after recreate"))
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should create and restore legacy log directory link`() {
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.linkLegacyLogDir(legacyLogDir)
            assertTrue(Files.isSymbolicLink(legacyLogDir.toPath()))
            assertEquals(logDir.canonicalFile, legacyLogDir.canonicalFile)

            Files.delete(legacyLogDir.toPath())
            fileLogger.recreateIfDeleted()

            assertTrue(Files.isSymbolicLink(legacyLogDir.toPath()))
            assertEquals(logDir.canonicalFile, legacyLogDir.canonicalFile)
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should migrate existing legacy log directory`() {
        legacyLogDir.resolve("compile_old.log").apply {
            parentFile.mkdirs()
            writeText("old log")
        }
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.linkLegacyLogDir(legacyLogDir)
            assertTrue(Files.isSymbolicLink(legacyLogDir.toPath()))
            assertEquals("old log", logDir.resolve("compile_old.log").readText())
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should preserve both logs when legacy migration has a name conflict`() {
        logDir.resolve("compile_old.log").writeText("new log")
        legacyLogDir.resolve("compile_old.log").apply {
            parentFile.mkdirs()
            writeText("old log")
        }
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.linkLegacyLogDir(legacyLogDir)

            assertEquals("new log", logDir.resolve("compile_old.log").readText())
            assertEquals("old log", logDir.resolve("compile_old.log.legacy-1").readText())
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should replace legacy link pointing to another directory`() {
        val wrongTarget = legacyRoot.resolve("wrong-target").apply { mkdirs() }
        legacyLogDir.parentFile.mkdirs()
        Files.createSymbolicLink(legacyLogDir.toPath(), wrongTarget.toPath())
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.linkLegacyLogDir(legacyLogDir)

            assertEquals(logDir.canonicalFile, legacyLogDir.canonicalFile)
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should recover pending legacy directory backup before linking`() {
        legacyLogDir.parentFile.mkdirs()
        legacyLogDir.parentFile.resolve("log.migrating-test").apply {
            mkdirs()
            resolve("compile_old.log").writeText("old log")
        }
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.linkLegacyLogDir(legacyLogDir)

            assertTrue(Files.isSymbolicLink(legacyLogDir.toPath()))
            assertEquals("old log", logDir.resolve("compile_old.log").readText())
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should ignore completed legacy migration cleanup residue`() {
        legacyLogDir.parentFile.resolve("log.migrated-test").apply {
            mkdirs()
            resolve("compile_old.log").writeText("old log")
        }
        val fileLogger = FileLogger(logDir)

        try {
            fileLogger.linkLegacyLogDir(legacyLogDir)

            assertTrue(Files.isSymbolicLink(legacyLogDir.toPath()))
            assertFalse(logDir.resolve("compile_old.log").exists())
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should restore legacy directory when migration fails`() {
        assumeTrue(logDir.toPath().fileSystem.supportedFileAttributeViews().contains("posix"))
        val firstLog = legacyLogDir.resolve("a.log").apply {
            parentFile.mkdirs()
            writeText("first log")
        }
        val unreadableLog = legacyLogDir.resolve("b.log").apply { writeText("second log") }
        val fileLogger = FileLogger(logDir)
        val originalPermissions = Files.getPosixFilePermissions(unreadableLog.toPath())
        val readOnlyPermissions = originalPermissions - setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
        )

        try {
            Files.setPosixFilePermissions(unreadableLog.toPath(), readOnlyPermissions)
            assumeFalse(Files.isReadable(unreadableLog.toPath()))
            fileLogger.linkLegacyLogDir(legacyLogDir)

            assertFalse(Files.isSymbolicLink(legacyLogDir.toPath()))
            assertEquals("first log", firstLog.readText())
            assertTrue(unreadableLog.exists())
            assertFalse(logDir.resolve("a.log").exists())
        } finally {
            Files.setPosixFilePermissions(unreadableLog.toPath(), originalPermissions)
            fileLogger.dispose()
        }
    }

    @Test
    fun `should rotate paged main log files when size limit is exceeded`() {
        val fileLogger = FileLogger(logDir, limitBytes = 128, fileCount = 2)

        try {
            repeat(8) { index ->
                fileLogger.logger.info("message-$index-" + "x".repeat(64))
            }
            // Records are written by a dedicated writer thread, and a rotation recreates the active file
            // empty before the current record is appended. Drain the handler so the paged files are final.
            fileLogger.logger.handlers.forEach { it.flush() }

            val mainLogs = mainLogFiles().sortedBy { it.name }
            assertEquals(listOf("0", "1"), mainLogs.map { MAIN_LOG_GENERATION_REGEX.matchEntire(it.name)?.groupValues?.get(1) })
            assertTrue(mainLogs.all { it.length() > 0L })
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `should keep one log record in a single file when rotation happens`() {
        val fileLogger = FileLogger(logDir, limitBytes = 256, fileCount = 2)
        val largeMessage = "record-boundary-" + "y".repeat(160)

        try {
            fileLogger.logger.info("padding-" + "x".repeat(120))
            waitUntil { mainLogFiles().singleOrNull()?.length()?.let { it > 0L } == true }

            fileLogger.logger.info(largeMessage)
            waitUntil {
                mainLogFiles().size == 2 && mainLogFiles().count { it.readText().contains(largeMessage) } == 1
            }

            val logsContainingRecord = mainLogFiles().filter { it.readText().contains(largeMessage) }
            assertEquals(1, logsContainingRecord.size)
            assertTrue(logsContainingRecord.single().readText().contains(largeMessage))
        } finally {
            fileLogger.dispose()
        }
    }

    @Test
    fun `handler should use blocking queue for writer thread architecture`() {
        val hasBlockingQueueField = NoLockRotatingFileHandler::class.java.declaredFields.any {
            BlockingQueue::class.java.isAssignableFrom(it.type)
        }

        assertTrue(hasBlockingQueueField)
    }

    @Test
    fun `handler should cache current active file size`() {
        val hasCurrentFileSizeField = NoLockRotatingFileHandler::class.java.declaredFields.any {
            it.name == "currentFileSize" && (it.type == Int::class.javaPrimitiveType || it.type == Long::class.javaPrimitiveType)
        }

        assertTrue(hasCurrentFileSizeField)
    }

    private fun mainLogFiles(): List<File> {
        return logDir.listFiles()
            ?.filter { it.isFile && it.name.matches(MAIN_LOG_REGEX) }
            .orEmpty()
    }

    private fun latestLogFile() = logDir.resolve(LATEST_LOG_NAME)

    private fun lastLatestLogFile() = logDir.resolve(LAST_LATEST_LOG_NAME)

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(20)
        }
        assertTrue(condition(), "Condition not met within ${timeoutMs}ms")
    }
}
