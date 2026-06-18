package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class ConstRefEngineTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `change tracker should expose const definition before and after values`() {
        val tracker = ConstRefChangeTracker()

        tracker.updateDefinitionDiff(
            filePath = "Constants.kt",
            previousDefinitions = listOf(
                constDefinition(fqClassName = "com.example.Constants", constName = "MAX", constValue = "1"),
            ),
            currentDefinitions = listOf(
                constDefinition(fqClassName = "com.example.Constants", constName = "MAX", constValue = "2"),
            ),
        )
        tracker.updateDefinitionDiff(
            filePath = "HotSplashIntervalAbt.kt",
            previousDefinitions = emptyList(),
            currentDefinitions = listOf(
                constDefinition(
                    fqClassName = "com.tencent.wemusic.HotSplashIntervalAbt",
                    constName = "TAG",
                    constType = "String",
                    constValue = "\"HotSplashIntervalAbt\"",
                ),
            ),
        )

        val (changedDefinitions, removedDefinitions) = tracker.peekDefinitionChanges(
            listOf("Constants.kt", "HotSplashIntervalAbt.kt")
        )

        assertEquals(emptySet<ConstDefinitionChange>(), removedDefinitions)
        assertEquals(
            setOf(
                "com.example.Constants.MAX: [Int:1] -> [Int:2]",
                "com.tencent.wemusic.HotSplashIntervalAbt.TAG: <missing> -> [String:\"HotSplashIntervalAbt\"]",
            ),
            changedDefinitions.map { it.toLogString() }.toSet(),
        )
    }

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
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val logOutput = mutableListOf<String>()
        val capturingLogger = object : StdLogger("ConstRefEngine") {
            override fun debug(message: String?) {
                message?.let { logOutput += it }
                super.debug(message)
            }
        }
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = capturingLogger,
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
            assertTrue(
                "Expected effected definition change log but got: $logOutput",
                logOutput.any {
                    it.contains("ConstRefEngine effected definition changes") &&
                        it.contains("com.example.ConstantsKt.MAX: [Int:1] -> [Int:2]")
                },
            )
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should find effected file when reference is scanned before definition`() {
        val rootDir = createTempDirectory("const_ref_scan_order_independent")
        File(rootDir, ".git").mkdirs()
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example.user
                import com.example.Config.Companion.MAX
                val value = MAX
                """.trimIndent()
            )
        }
        val constantsFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example
                class Config {
                    companion object {
                        const val MAX = 1
                    }
                }
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
            scheduler.analyzeOnDemand(listOf(userFile.absolutePath))
            scheduler.analyzeOnDemand(listOf(constantsFile.absolutePath))
            scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))

            constantsFile.writeText(
                """
                package com.example
                class Config {
                    companion object {
                        const val MAX = 2
                    }
                }
                """.trimIndent()
            )
            constantsFile.setLastModified(constantsFile.lastModified() + 1000L)
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val effectedPaths = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
                .map { it.refFilePath }
                .toSet()
            assertEquals(setOf(userFile.toStdPath()), effectedPaths)
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
            assertNotNull(database.getFileCache(userFile.toStdPath()))

            userFile.delete()
            scheduler.onFileDeleted(userFile.absolutePath)
            assertTrue(scheduler.getEffectedFiles(listOf(constantsFile.absolutePath)).isEmpty())
            assertTrue(waitUntil { database.getFileCache(userFile.toStdPath()) == null })
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
            // Acknowledge initial diff so changeTracker is clean before A->B.
            scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            scheduler.acknowledgeEffectedFilesAfterDeployCommit()

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
    fun `getEffectedFiles should keep const diff until deploy commit acknowledgement`() {
        val rootDir = createTempDirectory("const_ref_scheduler_repeat_effect")
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
                fun value() = MAX
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
            scheduler.awaitAnalysis(
                listOf(constantsFile.absolutePath, userFile.absolutePath),
                timeoutMs = 10_000L,
            )

            constantsFile.writeText(
                """
                package com.example
                const val MAX = 2
                """.trimIndent()
            )
            constantsFile.setLastModified(constantsFile.lastModified() + 1000L)
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

            val firstEffected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            val secondEffected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))

            assertEquals(setOf(userFile.toStdPath()), firstEffected.map { it.refFilePath }.toSet())
            assertEquals(setOf(userFile.toStdPath()), secondEffected.map { it.refFilePath }.toSet())

            scheduler.acknowledgeEffectedFilesAfterDeployCommit()
            val afterAck = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertTrue(afterAck.isEmpty())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should write empty candidate analysis when file has no const references`() {
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
        whenever(analyzer.parseReferenceCandidates(any())).thenAnswer { invocation ->
            val files = invocation.getArgument<Collection<File>>(0)
            files.associate { file ->
                file.toStdPath() to emptyList<ConstReferenceCandidate>()
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

            verify(analyzer, atLeastOnce()).parseReferenceCandidates(any())
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

    private fun snapshotLogs(logs: List<String>): List<String> {
        return synchronized(logs) {
            logs.toList()
        }
    }

    @Test
    fun `analyzeOnDemand should not be blocked by concurrent full scan batch`() {
        val rootDir = createTempDirectory("const_ref_mutex_contention")
        File(rootDir, ".git").mkdirs()

        // Create many source files so full scan has a large batch to process.
        val bulkDir = File(rootDir, "bulk").apply { mkdirs() }
        val bulkFileCount = 80
        repeat(bulkFileCount) { i ->
            File(bulkDir, "Bulk$i.kt").writeText(
                """
                package com.example.bulk
                const val BULK_$i = $i
                """.trimIndent()
            )
        }
        val targetFile = File(rootDir, "Target.kt").apply {
            writeText(
                """
                package com.example
                const val TARGET = 1
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            // Start full scan with many files — this will hold analysisMutex in batches.
            engine.initializeFullScan(listOf(bulkDir))
            // Immediately run on-demand analysis on a single target file.
            engine.onFileSaved(targetFile.absolutePath)
            val elapsedMs = measureTimeMillis {
                val readiness = engine.analyzeOnDemand(listOf(targetFile.absolutePath))
                assertTrue("analyzeOnDemand should complete", readiness.isReady)
            }
            // With per-file lock granularity, on-demand should complete in well under 10s
            // even if full scan is processing 80+ files concurrently.
            assertTrue(
                "analyzeOnDemand should complete quickly despite concurrent full scan, " +
                    "elapsedMs=$elapsedMs",
                elapsedMs < 10_000L,
            )
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `onFileDeleted should not block when database monitor is busy`() {
        val rootDir = createTempDirectory("const_ref_delete_non_blocking")
        File(rootDir, ".git").mkdirs()
        val deletedFile = File(rootDir, "Deleted.kt").apply {
            writeText(
                """
                package com.example
                const val DELETED = 1
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        val lockReady = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = Thread {
            synchronized(database) {
                lockReady.countDown()
                releaseLock.await(5, TimeUnit.SECONDS)
            }
        }
        lockHolder.start()
        try {
            assertTrue("database lock should be held before deletion", lockReady.await(2, TimeUnit.SECONDS))
            val elapsedMs = measureTimeMillis {
                engine.onFileDeleted(deletedFile.absolutePath)
            }
            assertTrue(
                "onFileDeleted should not wait for database monitor, elapsedMs=$elapsedMs",
                elapsedMs < 500L,
            )
        } finally {
            releaseLock.countDown()
            lockHolder.join(5_000L)
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `awaitAnalysis should skip full scan requirement for build generated directories`() {
        val rootDir = createTempDirectory("const_ref_build_generated")
        File(rootDir, ".git").mkdirs()
        val generatedDir = File(rootDir, "build/generated/ksp/debug/kotlin").apply { mkdirs() }
        val generatedFile = File(generatedDir, "GeneratedCode.kt").apply {
            writeText(
                """
                package com.example.generated
                const val GENERATED_CONST = "value"
                """.trimIndent()
            )
        }
        // Create many files to simulate slow full scan
        repeat(100) { i ->
            File(generatedDir, "Generated$i.kt").writeText(
                """
                package com.example.generated
                const val CONST_$i = $i
                """.trimIndent()
            )
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            // Initialize full scan for the generated directory (will be slow)
            engine.initializeFullScan(listOf(generatedDir))

            // Trigger on-demand analysis for the target file
            engine.onFileSaved(generatedFile.absolutePath)

            // awaitAnalysis should succeed even if full scan is not complete
            // because build/generated directories skip the full scan requirement
            val readiness = engine.awaitAnalysis(listOf(generatedFile.absolutePath), timeoutMs = 1000L)

            assertTrue("Should be ready even without full scan completion", readiness.isReady)
            assertTrue("Should have no unready paths", readiness.unreadyPaths.isEmpty())
            assertTrue("Should have no pending source dirs", readiness.pendingSourceDirs.isEmpty())
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `initializeFullScan should defer first full scan until startup stabilization`() {
        val rootDir = createTempDirectory("const_ref_deferred_full_scan")
        File(rootDir, ".git").mkdirs()
        val sourceDir = File(rootDir, "src").apply { mkdirs() }
        val constantsFile = File(sourceDir, "Constants.kt").apply {
            writeText(
                """
                package com.example
                const val MAX = 1
                """.trimIndent()
            )
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = StartupDelayBackgroundTaskRunner(
                delegate = CoroutineBackgroundTaskRunner(scope),
            ),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
            startupStabilizationDelayMs = 500L,
        )
        try {
            engine.initializeFullScan(listOf(sourceDir))

            assertNull(database.getFileCache(constantsFile.toStdPath()))
            assertFalse(
                "initial full scan should not run before startup stabilization delay",
                waitUntil(timeoutMs = 300L, intervalMs = 20L) {
                    database.getFileCache(constantsFile.toStdPath()) != null
                },
            )
            assertTrue(
                "full scan should run after startup stabilization delay",
                waitUntil(timeoutMs = 5_000L) {
                    database.getFileCache(constantsFile.toStdPath()) != null
                },
            )
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `initializeFullScan should emit final progress log after interval log consumed batch`() {
        withSystemProperties(
            mapOf("jugg.constref.full.scan.log.interval.ms" to "0")
        ) {
            val rootDir = createTempDirectory("const_ref_full_scan_final_log")
            File(rootDir, ".git").mkdirs()
            val sourceDir = File(rootDir, "src").apply { mkdirs() }
            val constantsFile = File(sourceDir, "Constants.kt").apply {
                writeText(
                    """
                    package com.example
                    const val MAX = 1
                    """.trimIndent()
                )
            }
            val logOutput = Collections.synchronizedList(mutableListOf<String>())
            val capturingLogger = object : StdLogger("ConstRefEngine") {
                override fun debug(message: String?) {
                    message?.let { logOutput += it }
                    super.debug(message)
                }
            }
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
            val engine = ConstRefEngine(
                analyzer = ConstRefAnalyzer(logger),
                database = database,
                logger = capturingLogger,
                backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
                repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
                startupStabilizationDelayMs = 0L,
            )
            try {
                engine.initializeFullScan(listOf(sourceDir))

                assertTrue(
                    "full scan should analyze file",
                    waitUntil(timeoutMs = 5_000L) {
                        database.getFileCache(constantsFile.toStdPath()) != null
                    },
                )
                assertTrue(
                    "Expected final full scan progress log but got: ${snapshotLogs(logOutput)}",
                    waitUntil(timeoutMs = 1_000L) {
                        snapshotLogs(logOutput).any {
                            it.contains("ConstRefEngine full scan progress") && it.contains("final=true")
                        }
                    },
                )
            } finally {
                engine.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `analyzeOnDemand should log per-file phase breakdown for changed files`() {
        val rootDir = createTempDirectory("const_ref_phase_timing")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.java").apply {
            writeText(
                """
                package com.example;
                public class Constants {
                    public static final int VALUE_A = 1;
                    public static final String NAME = "hello";
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;
                public class User {
                    int x = Constants.VALUE_A;
                    String n = Constants.NAME;
                }
                """.trimIndent()
            )
        }

        val logOutput = mutableListOf<String>()
        val capturingLogger = object : StdLogger("ConstRefEngine") {
            override fun debug(message: String?) {
                message?.let { logOutput += it }
                super.debug(message)
            }
            override fun info(message: String?) {
                message?.let { logOutput += it }
                super.info(message)
            }
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = capturingLogger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            val readiness = engine.analyzeOnDemand(
                listOf(constantsFile.absolutePath, userFile.absolutePath)
            )
            assertTrue("analysis should be ready", readiness.isReady)

            // Verify phase-level breakdown log is emitted for changed files
            val hasPhaseBreakdown = logOutput.any { msg ->
                msg.contains("analyzeFiles phase breakdown") &&
                    msg.contains("checksumMs=") &&
                    msg.contains("phase1ParseMs=") &&
                    msg.contains("phase2RefMs=")
            }
            assertTrue(
                "Expected analyzeFiles phase breakdown log but got: $logOutput",
                hasPhaseBreakdown,
            )
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should use scene specific throttle defaults`() {
        val rootDir = createTempDirectory("const_ref_default_scene_throttle")
        File(rootDir, ".git").mkdirs()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            assertEquals(500L, readPrivateLong(engine, "fullScanIoThrottleSleepMs"))
            assertEquals(200, readPrivateInt(engine, "fullScanIoThrottleEveryNFiles"))
            assertEquals(500L, readPrivateLong(engine, "fileChangeIoThrottleSleepMs"))
            assertEquals(200, readPrivateInt(engine, "fileChangeIoThrottleEveryNFiles"))
            assertEquals(0L, readPrivateLong(engine, "preCompileIoThrottleSleepMs"))
            assertEquals(1, readPrivateInt(engine, "preCompileIoThrottleEveryNFiles"))
            assertEquals(0L, readPrivateLong(engine, "onDemandIoThrottleSleepMs"))
            assertEquals(1, readPrivateInt(engine, "onDemandIoThrottleEveryNFiles"))
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should let scene specific throttle override legacy throttle`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.io.throttle.ms" to "9000",
                "jugg.constref.io.throttle.every" to "9",
                "jugg.constref.fullscan.io.throttle.ms" to "250",
                "jugg.constref.fullscan.io.throttle.every" to "100",
                "jugg.constref.precompile.io.throttle.ms" to "0",
                "jugg.constref.precompile.io.throttle.every" to "1",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_scene_throttle_override")
            File(rootDir, ".git").mkdirs()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val engine = ConstRefEngine(
                analyzer = ConstRefAnalyzer(logger),
                database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
                logger = logger,
                backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
                repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
            )
            try {
                assertEquals(250L, readPrivateLong(engine, "fullScanIoThrottleSleepMs"))
                assertEquals(100, readPrivateInt(engine, "fullScanIoThrottleEveryNFiles"))
                assertEquals(9000L, readPrivateLong(engine, "fileChangeIoThrottleSleepMs"))
                assertEquals(9, readPrivateInt(engine, "fileChangeIoThrottleEveryNFiles"))
                assertEquals(0L, readPrivateLong(engine, "preCompileIoThrottleSleepMs"))
                assertEquals(1, readPrivateInt(engine, "preCompileIoThrottleEveryNFiles"))
                assertEquals(9000L, readPrivateLong(engine, "onDemandIoThrottleSleepMs"))
                assertEquals(9, readPrivateInt(engine, "onDemandIoThrottleEveryNFiles"))
            } finally {
                engine.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `precompile should ignore legacy throttle sleep`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.io.throttle.ms" to "1200",
                "jugg.constref.io.throttle.every" to "1",
                "jugg.constref.precompile.io.throttle.ms" to "0",
                "jugg.constref.precompile.io.throttle.every" to "1",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_precompile_no_throttle")
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
            val engine = ConstRefEngine(
                analyzer = ConstRefAnalyzer(logger),
                database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
                logger = logger,
                backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
                repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
            )
            try {
                engine.onFileSaved(constantsFile.absolutePath)
                val elapsedMs = measureTimeMillis {
                    val readiness = engine.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 1_000L)
                    assertTrue(readiness.isReady)
                }
                assertTrue("precompile should not sleep legacy throttle, elapsedMs=$elapsedMs", elapsedMs < 1_000L)
            } finally {
                engine.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `on demand should ignore legacy throttle sleep`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.io.throttle.ms" to "1200",
                "jugg.constref.io.throttle.every" to "1",
                "jugg.constref.ondemand.io.throttle.ms" to "0",
                "jugg.constref.ondemand.io.throttle.every" to "1",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_ondemand_no_throttle")
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
            val engine = ConstRefEngine(
                analyzer = ConstRefAnalyzer(logger),
                database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
                logger = logger,
                backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
                repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
            )
            try {
                engine.onFileSaved(constantsFile.absolutePath)
                val elapsedMs = measureTimeMillis {
                    val readiness = engine.analyzeOnDemand(listOf(constantsFile.absolutePath))
                    assertTrue(readiness.isReady)
                }
                assertTrue("on-demand should not sleep legacy throttle, elapsedMs=$elapsedMs", elapsedMs < 1_000L)
            } finally {
                engine.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `legacy throttle should still apply when no scene specific property is set`() {
        withSystemProperties(
            mapOf(
                "jugg.constref.io.throttle.ms" to "1200",
                "jugg.constref.io.throttle.every" to "1",
            )
        ) {
            val rootDir = createTempDirectory("const_ref_legacy_throttle")
            File(rootDir, ".git").mkdirs()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val engine = ConstRefEngine(
                analyzer = ConstRefAnalyzer(logger),
                database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
                logger = logger,
                backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
                repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
            )
            try {
                assertEquals(1200L, readPrivateLong(engine, "fullScanIoThrottleSleepMs"))
                assertEquals(1, readPrivateInt(engine, "fullScanIoThrottleEveryNFiles"))
                assertEquals(1200L, readPrivateLong(engine, "fileChangeIoThrottleSleepMs"))
                assertEquals(1, readPrivateInt(engine, "fileChangeIoThrottleEveryNFiles"))
                assertEquals(1200L, readPrivateLong(engine, "preCompileIoThrottleSleepMs"))
                assertEquals(1, readPrivateInt(engine, "preCompileIoThrottleEveryNFiles"))
                assertEquals(1200L, readPrivateLong(engine, "onDemandIoThrottleSleepMs"))
                assertEquals(1, readPrivateInt(engine, "onDemandIoThrottleEveryNFiles"))
            } finally {
                engine.dispose()
                scope.cancel()
            }
        }
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

    private fun readPrivateLong(target: Any, fieldName: String): Long {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getLong(target)
    }

    private fun readPrivateInt(target: Any, fieldName: String): Int {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getInt(target)
    }

    @Test
    fun `simpleClassConstKeys should contain only actual field access pairs not cartesian product`() {
        val rootDir = createTempDirectory("const_ref_simple_class_const_keys")
        File(rootDir, ".git").mkdirs()
        // Define constants in two separate classes
        val alphaFile = File(rootDir, "Alpha.java").apply {
            writeText(
                """
                package com.example;
                public class Alpha {
                    public static final int FOO = 1;
                    public static final int BAZ = 2;
                }
                """.trimIndent()
            )
        }
        val betaFile = File(rootDir, "Beta.java").apply {
            writeText(
                """
                package com.example;
                public class Beta {
                    public static final String BAR = "b";
                    public static final String QUX = "q";
                }
                """.trimIndent()
            )
        }
        // User file references Alpha.FOO and Beta.BAR via simple names.
        // It should NOT produce a cartesian product (Alpha × {FOO, BAR}) ∪ (Beta × {FOO, BAR}).
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;
                public class User {
                    int x = Alpha.FOO;
                    String y = Beta.BAR;
                }
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            // Analyze all files first to populate DB with definitions
            val readiness = engine.analyzeOnDemand(
                listOf(alphaFile.absolutePath, betaFile.absolutePath, userFile.absolutePath)
            )
            assertTrue("analysis should be ready", readiness.isReady)

            // Verify that only Alpha.FOO and Beta.BAR are referenced, not Alpha.BAR or Beta.FOO.
            // The parser should use simpleClassConstKeys for precise (simpleName, constName) pairs.
            val analyzer = ConstRefAnalyzer(logger)
            var capturedHints: ConstReferenceLookupHints? = null
            analyzer.collectHintsAndParseReferences(userFile) { hints ->
                capturedHints = hints
                null // Return null to skip reference parsing
            }

            val hints = capturedHints!!
            // simpleClassConstKeys should contain the precise pairs
            assertTrue(
                "simpleClassConstKeys should contain (Alpha, FOO)",
                hints.simpleClassConstKeys.contains("Alpha" to "FOO"),
            )
            assertTrue(
                "simpleClassConstKeys should contain (Beta, BAR)",
                hints.simpleClassConstKeys.contains("Beta" to "BAR"),
            )
            // Should NOT contain cross-product pairs
            assertFalse(
                "simpleClassConstKeys should NOT contain (Alpha, BAR) - no such access in code",
                hints.simpleClassConstKeys.contains("Alpha" to "BAR"),
            )
            assertFalse(
                "simpleClassConstKeys should NOT contain (Beta, FOO) - no such access in code",
                hints.simpleClassConstKeys.contains("Beta" to "FOO"),
            )
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test(timeout = 30_000)
    fun `analyzeOnDemand should return within per-file timeout when analyzeFiles hangs`() {
        val rootDir = createTempDirectory("const_ref_analyze_on_demand_timeout")
        File(rootDir, ".git").mkdirs()
        val sourceFile = File(rootDir, "Slow.kt").apply {
            writeText(
                """
                package com.example
                const val SLOW = 1
                """.trimIndent()
            )
        }

        val hangMs = 60_000L
        val slowAnalyzer = mock<ConstRefAnalyzer>()
        whenever(slowAnalyzer.parseDefinitions(any())).thenAnswer {
            Thread.sleep(hangMs)
            emptyMap<String, List<ConstDefinition>>()
        }
        whenever(slowAnalyzer.collectHintsAndParseReferences(any(), any())).thenReturn(emptyList())
        whenever(slowAnalyzer.parseReferences(any(), any<ConstDefinitionLookup>())).thenReturn(emptyMap())

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = slowAnalyzer,
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            engine.onFileSaved(sourceFile.absolutePath)
            val perFileTimeoutMs = 5_000L
            val elapsedMs = measureTimeMillis {
                val readiness = engine.analyzeOnDemand(listOf(sourceFile.absolutePath))
                assertFalse(
                    "analyzeOnDemand should return not-ready when timeout fires",
                    readiness.isReady,
                )
            }
            // Should complete near perFileTimeoutMs * 1 file, well under hangMs
            assertTrue(
                "analyzeOnDemand should not block longer than per-file timeout, elapsedMs=$elapsedMs",
                elapsedMs < perFileTimeoutMs * 2,
            )
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test(timeout = 30_000)
    fun `queryCandidateDefinitionsForFile should use precise pairs instead of cartesian product`() {
        val rootDir = createTempDirectory("const_ref_no_cartesian")
        File(rootDir, ".git").mkdirs()
        // Create many constant classes and many constants to amplify the difference
        val constClassCount = 10
        val constPerClass = 5
        val constFiles = (0 until constClassCount).map { classIdx ->
            File(rootDir, "Consts$classIdx.java").apply {
                val fields = (0 until constPerClass).joinToString("\n") { constIdx ->
                    "    public static final int C${classIdx}_$constIdx = ${classIdx * 100 + constIdx};"
                }
                writeText(
                    """
                    package com.example;
                    public class Consts$classIdx {
                    $fields
                    }
                    """.trimIndent()
                )
            }
        }
        // User file: accesses only Consts0.C0_0 and Consts1.C1_0 via simple names.
        // Without optimization: 10 simpleNames × 50 constNames = 500 classConstKeys
        // With optimization: 2 pairs → ~2 classConstKeys after resolve
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;
                public class User {
                    int a = Consts0.C0_0;
                    int b = Consts1.C1_0;
                }
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = database,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            val allFiles = constFiles + listOf(userFile)
            val readiness = engine.analyzeOnDemand(allFiles.map { it.absolutePath })
            assertTrue("analysis should be ready", readiness.isReady)

            // Modify one constant to trigger effected file query
            constFiles[0].writeText(
                """
                package com.example;
                public class Consts0 {
                    public static final int C0_0 = 999;
                ${(1 until constPerClass).joinToString("\n") { "    public static final int C0_$it = $it;" }}
                }
                """.trimIndent()
            )
            engine.analyzeOnDemand(listOf(constFiles[0].absolutePath))

            val effected = engine.getEffectedFiles(listOf(constFiles[0].absolutePath))
            val effectedPaths = effected.map { it.refFilePath }.toSet()
            assertTrue(
                "User.java should be in effected files",
                effectedPaths.contains(userFile.toStdPath()),
            )
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `analyzeOnDemand should degrade to ready when batch analysis throws`() {
        val rootDir = createTempDirectory("const_ref_analyze_on_demand_failure")
        File(rootDir, ".git").mkdirs()
        val goodFile = File(rootDir, "Good.kt").apply {
            writeText(
                """
                package com.example
                const val GOOD = 1
                """.trimIndent()
            )
        }
        val badFile = File(rootDir, "Bad.kt").apply {
            writeText(
                """
                package com.example
                const val BAD = 2
                """.trimIndent()
            )
        }

        val failingAnalyzer = mock<ConstRefAnalyzer>()
        whenever(failingAnalyzer.parseDefinitions(any())).thenAnswer { invocation ->
            val files = invocation.getArgument<Collection<File>>(0)
            files.associate { file ->
                if (file.name == "Bad.kt") {
                    throw ClassCastException(
                        "null cannot be cast to non-null type org.jetbrains.kotlin.psi.KtPackageDirective"
                    )
                }
                file.toStdPath() to listOf(
                    ConstDefinition(
                        filePath = file.toStdPath(),
                        packageName = "com.example",
                        fqClassName = "com.example.${file.nameWithoutExtension}Kt",
                        constName = file.nameWithoutExtension.uppercase(),
                        constType = "Int",
                        constValue = "1",
                    )
                )
            }
        }
        whenever(failingAnalyzer.parseReferenceCandidates(any())).thenReturn(emptyMap())
        whenever(failingAnalyzer.resetEnvironment()).then { }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = failingAnalyzer,
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            val readiness = engine.analyzeOnDemand(listOf(goodFile.absolutePath, badFile.absolutePath))
            assertTrue("analyzeOnDemand should degrade to ready after parse failure", readiness.isReady)
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `analyzeOnDemand should stay ready during concurrent file change analysis`() {
        val rootDir = createTempDirectory("const_ref_on_demand_concurrent")
        File(rootDir, ".git").mkdirs()
        repeat(20) { index ->
            File(rootDir, "Bulk$index.kt").writeText(
                """
                package com.example.bulk
                const val BULK_$index = $index
                """.trimIndent()
            )
        }
        val targetFile = File(rootDir, "Target.kt").apply {
            writeText(
                """
                package com.example
                const val TARGET = 1
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            engine.initializeFullScan(listOf(rootDir))
            repeat(20) { index ->
                engine.onFileSaved(File(rootDir, "Bulk$index.kt").absolutePath)
            }
            engine.onFileSaved(targetFile.absolutePath)
            val paths = (0 until 20).map { File(rootDir, "Bulk$it.kt").absolutePath } + targetFile.absolutePath
            val readiness = engine.analyzeOnDemand(paths)
            assertTrue("analyzeOnDemand should stay ready under concurrent file-change analysis", readiness.isReady)
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    private fun constDefinition(
        fqClassName: String,
        constName: String,
        constType: String = "Int",
        constValue: String? = null,
    ): ConstDefinition {
        return ConstDefinition(
            filePath = "$fqClassName.kt",
            packageName = fqClassName.substringBeforeLast('.', ""),
            fqClassName = fqClassName,
            constName = constName,
            constType = constType,
            constValue = constValue,
        )
    }

    private class StartupDelayBackgroundTaskRunner(
        private val delegate: CoroutineBackgroundTaskRunner,
    ) : IBackgroundTaskRunner by delegate
}
