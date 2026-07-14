package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.createTestTaskRunnerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ConstRefIntegrationTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should detect effected files on cold start after full scan becomes ready`() {
        val rootDir = createTempDirectory("const_ref_integration")
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
        File(rootDir, "Unrelated.kt").writeText(
            """
            package com.example
            val noop = 1
            """.trimIndent()
        )

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.initializeFullScan(listOf(rootDir))
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
    fun `should detect effected files when companion const changes`() {
        val rootDir = createTempDirectory("const_ref_integration_companion")
        File(rootDir, ".git").mkdirs()
        val configFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example

                class Config {
                    companion object {
                        const val DEFAULT = "old"
                    }
                }
                """.trimIndent()
            )
        }
        val serviceFile = File(rootDir, "Service.kt").apply {
            writeText(
                """
                package com.example

                val current = Config.DEFAULT
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(configFile.absolutePath)
            scheduler.onFileSaved(serviceFile.absolutePath)
            scheduler.awaitAnalysis(listOf(configFile.absolutePath, serviceFile.absolutePath), timeoutMs = 10_000L)

            configFile.writeText(
                """
                package com.example

                class Config {
                    companion object {
                        const val DEFAULT = "new"
                    }
                }
                """.trimIndent()
            )
            scheduler.onFileSaved(configFile.absolutePath)
            scheduler.awaitAnalysis(listOf(configFile.absolutePath), timeoutMs = 10_000L)

            val effected = scheduler.getEffectedFiles(listOf(configFile.absolutePath))
            assertEquals(setOf(serviceFile.toStdPath()), effected.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should detect effected files when nested object const changes in same package`() {
        val rootDir = createTempDirectory("const_ref_integration_nested_object")
        File(rootDir, ".git").mkdirs()
        val configFile = File(rootDir, "NestedObjectConfig.kt").apply {
            writeText(nestedObjectConfigSource("old"))
        }
        val invokerFile = File(rootDir, "NestedObjectConfigInvoker.kt").apply {
            writeText(
                """
                package com.example

                val method = NestedObjectConfig.FeedbackServer.METHOD
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(configFile.absolutePath)
            scheduler.onFileSaved(invokerFile.absolutePath)
            scheduler.awaitAnalysis(listOf(configFile.absolutePath, invokerFile.absolutePath), timeoutMs = 10_000L)

            configFile.writeText(nestedObjectConfigSource("new"))
            scheduler.onFileSaved(configFile.absolutePath)
            scheduler.awaitAnalysis(listOf(configFile.absolutePath), timeoutMs = 10_000L)

            val effected = scheduler.getEffectedFiles(listOf(configFile.absolutePath))
            assertEquals(setOf(invokerFile.toStdPath()), effected.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    private fun nestedObjectConfigSource(method: String): String {
        return """
            package com.example

            object NestedObjectConfig {
                object FeedbackServer {
                    const val METHOD = "$method"
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not detect effected files when unrelated class changes`() {
        val rootDir = createTempDirectory("const_ref_integration_unrelated")
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
        val unrelatedFile = File(rootDir, "Unrelated.kt").apply {
            writeText(
                """
                package com.example
                class Unrelated {
                    fun run() = 1
                }
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.onFileSaved(unrelatedFile.absolutePath)
            scheduler.awaitAnalysis(
                listOf(constantsFile.absolutePath, userFile.absolutePath, unrelatedFile.absolutePath),
                timeoutMs = 10_000L,
            )

            unrelatedFile.writeText(
                """
                package com.example
                class Unrelated {
                    fun run() = 2
                }
                """.trimIndent()
            )
            scheduler.onFileSaved(unrelatedFile.absolutePath)
            scheduler.awaitAnalysis(listOf(unrelatedFile.absolutePath), timeoutMs = 10_000L)

            val effected = scheduler.getEffectedFiles(listOf(unrelatedFile.absolutePath))
            assertEquals(emptySet<String>(), effected.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should only detect references for truly changed constants`() {
        val rootDir = createTempDirectory("const_ref_integration_precise_change")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example

                const val MAX = 1
                const val MIN = 0
                """.trimIndent()
            )
        }
        val maxUserFile = File(rootDir, "MaxUser.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MAX
                val maxValue = MAX
                """.trimIndent()
            )
        }
        val minUserFile = File(rootDir, "MinUser.kt").apply {
            writeText(
                """
                package com.example
                import com.example.MIN
                val minValue = MIN
                """.trimIndent()
            )
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(maxUserFile.absolutePath)
            scheduler.onFileSaved(minUserFile.absolutePath)
            scheduler.awaitAnalysis(
                listOf(constantsFile.absolutePath, maxUserFile.absolutePath, minUserFile.absolutePath),
                timeoutMs = 10_000L,
            )

            constantsFile.writeText(
                """
                package com.example

                const val MAX = 1
                const val MIN = 0

                """.trimIndent()
            )
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)
            val effectedByWhitespaceChange = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(emptySet<String>(), effectedByWhitespaceChange.map { it.refFilePath }.toSet())

            constantsFile.writeText(
                """
                package com.example

                const val MAX = 2
                const val MIN = 0
                """.trimIndent()
            )
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)
            val effectedByMaxChange = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(setOf(maxUserFile.toStdPath()), effectedByMaxChange.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should clear stale const change when file analysis is reused`() {
        val rootDir = createTempDirectory("const_ref_integration_reuse")
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
            taskRunnerManager = createTestTaskRunnerManager(scope),
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
            val effectedAfterRealChange = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(setOf(userFile.toStdPath()), effectedAfterRealChange.map { it.refFilePath }.toSet())
            scheduler.acknowledgeEffectedFilesAfterDeployCommit()

            // Trigger analysis reuse path (same mtime/checksum mapping) without source changes.
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)
            val effectedAfterReuse = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(emptySet<String>(), effectedAfterReuse.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should clear stale const change after full scan reuse`() {
        val rootDir = createTempDirectory("const_ref_integration_full_scan_reset")
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
            taskRunnerManager = createTestTaskRunnerManager(scope),
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
            val effectedAfterRealChange = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(setOf(userFile.toStdPath()), effectedAfterRealChange.map { it.refFilePath }.toSet())
            scheduler.acknowledgeEffectedFilesAfterDeployCommit()

            // Simulate downgrade/full-build init path: full scan with mostly reusable cache.
            scheduler.initializeFullScan(listOf(rootDir))
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)
            val effectedAfterFullScan = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            assertEquals(emptySet<String>(), effectedAfterFullScan.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }
}
