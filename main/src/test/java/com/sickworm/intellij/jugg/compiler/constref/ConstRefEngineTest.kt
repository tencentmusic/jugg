package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class ConstRefEngineTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `analyzeOnDemand should analyze target file synchronously`() {
        val rootDir = createTempDirectory("const_ref_scheduler_on_demand")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            val readiness = scheduler.analyzeOnDemand(listOf(constantsFile.absolutePath))
            assertTrue(readiness.isReady)
            assertNotNull(database.getFileCache(constantsFile.toStdPath()))
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `awaitAnalysis should return within timeout when pending analysis is slow`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.io.throttle.ms" to "1200",
                "jugg.constref.io.throttle.every" to "1",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_scheduler_timeout_budget")
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
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val scope = CoroutineScope(dispatcher + SupervisorJob())
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
                val elapsedMs = measureTimeMillis {
                    val readiness = scheduler.awaitAnalysis(listOf(userFile.absolutePath), timeoutMs = 300L)
                    assertFalse(readiness.isReady)
                }
                assertTrue("awaitAnalysis should not block much longer than timeout, elapsedMs=$elapsedMs", elapsedMs < 1_000L)
            } finally {
                scheduler.dispose()
                scope.cancel()
                dispatcher.close()
            }
        }
    }

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

    @Test
    fun `should resolve effected files in db session lookup mode`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.session.file.cache.max" to "500",
                "jugg.constref.session.lookup.cache.max" to "4000",
                "jugg.constref.session.cache.ttl.ms" to "600000",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_scheduler_db_session")
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

                val effected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
                assertEquals(setOf(userFile.toStdPath()), effected.map { it.refFilePath }.toSet())
            } finally {
                scheduler.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `should keep result consistent after session cache eviction in db session mode`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.session.file.cache.max" to "1",
                "jugg.constref.session.lookup.cache.max" to "1",
                "jugg.constref.session.cache.ttl.ms" to "600000",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_scheduler_db_session_eviction")
            File(rootDir, ".git").mkdirs()
            val constantsA = File(rootDir, "ConstantsA.kt").apply {
                writeText(
                    """
                    package com.example
                    const val MAX_A = 1
                    """.trimIndent()
                )
            }
            val userA = File(rootDir, "UserA.kt").apply {
                writeText(
                    """
                    package com.example
                    import com.example.MAX_A
                    val valueA = MAX_A
                    """.trimIndent()
                )
            }
            val constantsB = File(rootDir, "ConstantsB.kt").apply {
                writeText(
                    """
                    package com.example
                    const val MAX_B = 1
                    """.trimIndent()
                )
            }
            val userB = File(rootDir, "UserB.kt").apply {
                writeText(
                    """
                    package com.example
                    import com.example.MAX_B
                    val valueB = MAX_B
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
                scheduler.onFileSaved(constantsA.absolutePath)
                scheduler.onFileSaved(userA.absolutePath)
                scheduler.onFileSaved(constantsB.absolutePath)
                scheduler.onFileSaved(userB.absolutePath)
                scheduler.awaitAnalysis(
                    listOf(constantsA.absolutePath, userA.absolutePath, constantsB.absolutePath, userB.absolutePath),
                    timeoutMs = 10_000L,
                )

                constantsA.writeText(
                    """
                    package com.example
                    const val MAX_A = 2
                    """.trimIndent()
                )
                scheduler.onFileSaved(constantsA.absolutePath)
                scheduler.awaitAnalysis(listOf(constantsA.absolutePath), timeoutMs = 10_000L)

                val effected = scheduler.getEffectedFiles(listOf(constantsA.absolutePath))
                assertEquals(setOf(userA.toStdPath()), effected.map { it.refFilePath }.toSet())
            } finally {
                scheduler.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `should detect const change when value reverts to a previously cached version (A-B-A)`() {
        val rootDir = createTempDirectory("const_ref_scheduler_aba")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val ROUTE = "page_a"
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example
                import com.example.ROUTE
                val r = ROUTE
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
            // Initial analysis with value A ("page_a")
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.awaitAnalysis(
                listOf(constantsFile.absolutePath, userFile.absolutePath),
                timeoutMs = 10_000L,
            )
            // Consume initial diff so changeTracker is clean
            scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))

            // Change to value B ("page_b")
            constantsFile.writeText(
                """
                package com.example
                const val ROUTE = "page_b"
                """.trimIndent()
            )
            constantsFile.setLastModified(constantsFile.lastModified() + 1000L)
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val effectedAfterB = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(
                "A->B should trigger recompile of referencing file",
                setOf(userFile.toStdPath()),
                effectedAfterB.map { it.refFilePath }.toSet(),
            )

            // Revert back to value A ("page_a") — this is the A→B→A scenario
            constantsFile.writeText(
                """
                package com.example
                const val ROUTE = "page_a"
                """.trimIndent()
            )
            constantsFile.setLastModified(constantsFile.lastModified() + 1000L)
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val effectedAfterRevert = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(
                "B->A (revert) should still trigger recompile of referencing file",
                setOf(userFile.toStdPath()),
                effectedAfterRevert.map { it.refFilePath }.toSet(),
            )
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should skip reference parsing when lookup hints are empty in db session mode`() {
        val rootDir = createTempDirectory("const_ref_scheduler_empty_hints")
        File(rootDir, ".git").mkdirs()
        val plainFile = File(rootDir, "Plain.kt").apply {
            writeText(
                """
                package com.example
                class Plain
                """.trimIndent()
            )
        }
        val analyzer = mock<ConstRefAnalyzer>()
        whenever(analyzer.parseDefinitions(any())).thenAnswer { invocation ->
            val files = invocation.getArgument<Collection<File>>(0)
            files.associate { file ->
                file.toStdPath() to emptyList<ConstDefinition>()
            }
        }
        whenever(analyzer.collectReferenceLookupHints(any())).thenAnswer { invocation ->
            val files = invocation.getArgument<Collection<File>>(0)
            files.associate { file ->
                file.toStdPath() to ConstReferenceLookupHints.EMPTY
            }
        }
        whenever(analyzer.parseReferences(any(), any<ConstDefinitionLookup>())).thenReturn(emptyMap())

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = analyzer,
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(plainFile.absolutePath)
            scheduler.awaitAnalysis(listOf(plainFile.absolutePath), timeoutMs = 10_000L)

            verify(analyzer, atLeastOnce()).collectReferenceLookupHints(any())
            verify(analyzer, never()).parseReferences(any(), any<ConstDefinitionLookup>())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should trigger recompile for pulled const change in another worktree on cold start`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.session.file.cache.max" to "500",
                "jugg.constref.session.lookup.cache.max" to "4000",
                "jugg.constref.session.cache.ttl.ms" to "600000",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_scheduler_worktree_pull")
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
            val userInA = File(worktreeA, "src/User.kt").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package com.example
                    import com.example.MAX
                    val value = MAX
                    """.trimIndent()
                )
            }
            val constantsInB = File(worktreeB, "src/Constants.kt").apply {
                parentFile.mkdirs()
                writeText(constantsInA.readText())
            }
            val userInB = File(worktreeB, "src/User.kt").apply {
                parentFile.mkdirs()
                writeText(userInA.readText())
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
                schedulerA.onFileSaved(userInA.absolutePath)
                schedulerA.awaitAnalysis(listOf(constantsInA.absolutePath, userInA.absolutePath), timeoutMs = 10_000L)
                schedulerA.getEffectedFiles(listOf(constantsInA.absolutePath))

                constantsInA.writeText(
                    """
                    package com.example
                    const val MAX = 2
                    """.trimIndent()
                )
                constantsInB.writeText(constantsInA.readText())
                constantsInB.setLastModified(constantsInA.lastModified())
                schedulerA.onFileSaved(constantsInA.absolutePath)
                schedulerA.awaitAnalysis(listOf(constantsInA.absolutePath), timeoutMs = 10_000L)
                schedulerA.getEffectedFiles(listOf(constantsInA.absolutePath))
            } finally {
                schedulerA.dispose()
                scopeA.cancel()
            }

            val scopeB = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val schedulerB = ConstRefEngine(
                analyzer = ConstRefAnalyzer(logger),
                database = ConstRefCacheDatabase(sharedDbFile, logger),
                logger = logger,
                backgroundTaskRunner = CoroutineBackgroundTaskRunner(scopeB),
                repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, sharedFingerprintDbFile),
            )
            try {
                schedulerB.onFileSaved(constantsInB.absolutePath)
                schedulerB.onFileSaved(userInB.absolutePath)
                schedulerB.awaitAnalysis(listOf(constantsInB.absolutePath, userInB.absolutePath), timeoutMs = 10_000L)

                val effected = schedulerB.getEffectedFiles(listOf(constantsInB.absolutePath))
                assertEquals(
                    "Pulled const change in another worktree should trigger one-time safe recompile",
                    setOf(userInB.toStdPath()),
                    effected.map { it.refFilePath }.toSet(),
                )
            } finally {
                schedulerB.dispose()
                scopeB.cancel()
            }
        }
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

    private inline fun <T> withSystemProperties(properties: Map<String, String>, action: () -> T): T {
        val previousValues = properties.mapValues { (key, _) -> System.getProperty(key) }
        properties.forEach { (key, value) -> System.setProperty(key, value) }
        return try {
            action()
        } finally {
            previousValues.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }
}
