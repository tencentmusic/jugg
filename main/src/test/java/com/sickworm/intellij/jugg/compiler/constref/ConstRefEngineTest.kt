package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConstRefEngineTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should not analyze current editing file until next file changes`() {
        val rootDir = createTempDirectory("const_ref_scheduler_editing_state")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(constantsFile.absolutePath)
            Thread.sleep(500L)
            assertNull(database.getFileCache(constantsFile.toStdPath()))

            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)
            assertNotNull(database.getFileCache(constantsFile.toStdPath()))
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should analyze previous file on next save and flush current editing file on await`() {
        val rootDir = createTempDirectory("const_ref_scheduler_next_file_trigger")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val value = MAX
                """.trimIndent()
            )
        }
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            assertTrue(waitUntil { database.getFileCache(constantsFile.toStdPath()) != null })
            assertNull(database.getFileCache(userFile.toStdPath()))

            scheduler.awaitAnalysis(listOf(userFile.absolutePath), timeoutMs = 10_000L)
            assertNotNull(database.getFileCache(userFile.toStdPath()))
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should analyze pending files and return effected files`() {
        val rootDir = createTempDirectory("const_ref_scheduler")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val value = MAX
                """.trimIndent()
            )
        }
        val adminFile = File(rootDir, "Admin.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val adminValue = MAX
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.onFileSaved(adminFile.absolutePath)
            scheduler.awaitAnalysis(
                listOf(constantsFile.absolutePath, userFile.absolutePath, adminFile.absolutePath),
                timeoutMs = 10_000L,
            )

            constantsFile.writeText(
                """
                package com.example
                const val MAX = 2
                """.trimIndent()
            )
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val effected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            val effectedPaths = effected.map { it.refFilePath }.toSet()
            assertEquals(setOf(userFile.toStdPath(), adminFile.toStdPath()), effectedPaths)
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should keep unconsumed const changes after analysis reuse`() {
        val rootDir = createTempDirectory("const_ref_scheduler_reuse_before_consume")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val value = MAX
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath, userFile.absolutePath), timeoutMs = 10_000L)

            constantsFile.writeText(
                """
                package com.example
                const val MAX = 2
                """.trimIndent()
            )
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            // Trigger reuse path before the first impacted-files query.
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val effected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(setOf(userFile.toStdPath()), effected.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should cleanup deleted file references`() {
        val rootDir = createTempDirectory("const_ref_scheduler_delete")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val value = MAX
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath, userFile.absolutePath), timeoutMs = 10_000L)

            constantsFile.writeText(
                """
                package com.example
                const val MAX = 2
                """.trimIndent()
            )
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)
            assertTrue(scheduler.getEffectedFiles(listOf(constantsFile.absolutePath)).isNotEmpty())

            userFile.delete()
            scheduler.onFileDeleted(userFile.absolutePath)
            assertTrue(scheduler.getEffectedFiles(listOf(constantsFile.absolutePath)).isEmpty())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should keep effected files when const is downgraded to val`() {
        val rootDir = createTempDirectory("const_ref_scheduler_downgrade")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val value = MAX
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath, userFile.absolutePath), timeoutMs = 10_000L)

            constantsFile.writeText(
                """
                package com.example
                val MAX = 2
                """.trimIndent()
            )
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val effected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(setOf(userFile.toStdPath()), effected.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should reuse analysis across worktrees when only mtime changes`() {
        val rootDir = createTempDirectory("const_ref_scheduler_worktree")
        val commonGitDir = File(rootDir, "common.git").apply { mkdirs() }
        val worktreeA = File(rootDir, "worktree_a").apply { mkdirs() }
        val worktreeB = File(rootDir, "worktree_b").apply { mkdirs() }
        prepareWorktreeGitRef(worktreeA, commonGitDir, "a")
        prepareWorktreeGitRef(worktreeB, commonGitDir, "b")

        val constantsInA = File(worktreeA, "src/Constants.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val constantsInB = File(worktreeB, "src/Constants.kt").apply {
            parentFile.mkdirs()
            writeText(constantsInA.readText())
            setLastModified(constantsInA.lastModified() + 10_000L)
        }

        val sharedDbFile = File(rootDir, "const_ref_shared.db")
        val sharedFingerprintDbFile = File(rootDir, "repo_fingerprint.db")
        val scopeA = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val schedulerA = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(sharedDbFile, logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scopeA),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, sharedFingerprintDbFile),
        )
        try {
            schedulerA.onFileSaved(constantsInA.absolutePath)
            schedulerA.awaitAnalysis(listOf(constantsInA.absolutePath), timeoutMs = 10_000L)
        } finally {
            schedulerA.dispose()
            scopeA.cancel()
        }

        val sharedDatabase = ConstRefCacheDatabase(sharedDbFile, logger)
        val before = sharedDatabase.getFileCache(constantsInA.toStdPath())
        assertNotNull(before)

        val scopeB = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val schedulerB = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = sharedDatabase,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scopeB),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, sharedFingerprintDbFile),
        )
        try {
            schedulerB.onFileSaved(constantsInB.absolutePath)
            schedulerB.awaitAnalysis(listOf(constantsInB.absolutePath), timeoutMs = 10_000L)
        } finally {
            schedulerB.dispose()
            scopeB.cancel()
        }

        val after = sharedDatabase.getFileCache(constantsInB.toStdPath())
        assertNotNull(after)
        assertEquals(before?.checksum, after?.checksum)
        assertEquals(before?.analyzedAt, after?.analyzedAt)
    }

    private fun prepareWorktreeGitRef(worktreeDir: File, commonGitDir: File, worktreeName: String) {
        val worktreeGitDir = File(commonGitDir, "worktrees/$worktreeName").apply { mkdirs() }
        File(worktreeGitDir, "commondir").writeText("../../\n")
        File(worktreeDir, ".git").writeText("gitdir: ${worktreeGitDir.absolutePath}\n")
    }

    private fun waitUntil(
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 30L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(intervalMs)
        }
        return condition()
    }
}
