package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
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
        val scheduler = ConstRefScheduler(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            coroutineScope = scope,
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            scheduler.initializeFullScan(listOf(rootDir))

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
        val scheduler = ConstRefScheduler(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            coroutineScope = scope,
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
        val scheduler = ConstRefScheduler(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            coroutineScope = scope,
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
}
