package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConstRefIntegrationTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should drop removed roots and rescan same-mtime content when rebuilding a baseline`() {
        val checkout = createTempDirectory("const_ref_final_sources")
        File(checkout, ".git").mkdirs()
        val earlyDir = File(checkout, "early").apply { mkdir() }
        val finalDir = File(checkout, "final").apply { mkdir() }
        val generated = File(finalDir, "Generated.java").apply {
            writeText("public class Generated { public static final int VALUE = 1; }")
        }
        val removed = File(earlyDir, "Removed.java").apply {
            writeText("public class Removed { public static final int VALUE = 3; }")
        }
        val databaseFile = File(checkout, "baseline.db")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            dbFile = databaseFile,
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            pathResolver = ConstRefRepoPathResolver(checkout),
        )
        try {
            runBlocking { engine.scanBaseline(listOf(earlyDir, finalDir)) }
            val lastModified = generated.lastModified()
            generated.writeText(generated.readText().replace("VALUE = 1", "VALUE = 2"))
            assertTrue(generated.setLastModified(lastModified))
            runBlocking { engine.scanBaseline(listOf(finalDir)) }
        } finally {
            engine.dispose()
            scope.cancel()
        }
        val database = ConstRefCacheDatabase(databaseFile, logger, ConstRefRepoPathResolver(checkout))
        try {
            database.registerPathHints(listOf(finalDir.toStdPath()))
            assertEquals(listOf("2"), database.getAllDefinitions().map { it.constValue })
            assertEquals(null, database.getFileCache(removed.toStdPath()))
        } finally {
            database.close()
        }
    }

    @Test
    fun `should reuse caller index and previous definitions after restoring baseline in another checkout`() {
        val original = createTempDirectory("const_ref_baseline")
        val restored = createTempDirectory("const_ref_checkout")
        listOf(original, restored).forEach { checkout ->
            File(checkout, ".git").mkdirs()
            File(checkout, "Constants.kt").writeText("package example\nobject Constants { const val VALUE = 1 }")
            File(checkout, "Caller.kt").writeText("package example\nval result = Constants.VALUE")
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        fun engine(checkout: File) = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            dbFile = File(checkout, "const_ref.db"),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            pathResolver = ConstRefRepoPathResolver(checkout),
        )
        try {
            val builder = engine(original)
            try {
                runBlocking { builder.scanBaseline(listOf(original)) }
            } finally {
                builder.dispose()
            }
            File(original, "const_ref.db").copyTo(File(restored, "const_ref.db"))
            val incremental = engine(restored)
            try {
                val constants = File(restored, "Constants.kt")
                val changed = listOf(constants.toStdPath())
                assertTrue(incremental.analyzeOnDemand(changed).isReady)
                assertTrue(incremental.getEffectedFiles(changed).isEmpty())

                constants.writeText(constants.readText().replace("VALUE = 1", "VALUE = 2"))
                assertTrue(incremental.analyzeOnDemand(changed).isReady)
                assertEquals(listOf(File(restored, "Caller.kt").toStdPath()),
                    incremental.getEffectedFiles(changed).map { it.refFilePath })
            } finally {
                incremental.dispose()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `should find callers in another repository of the project after restoring a baseline`() {
        val checkout = createTempDirectory("const_ref_cross_repository")
        File(checkout, ".git").mkdirs()
        val appSources = File(checkout, "app/src").apply { mkdirs() }
        val callerRepository = File(checkout, "caller-repo").apply { File(this, ".git").mkdirs() }
        val callerSources = File(callerRepository, "src").apply { mkdirs() }
        val constants = File(appSources, "Constants.java").apply {
            writeText("package fixture; public final class Constants { public static final int VALUE = 1; }")
        }
        val caller = File(callerSources, "Caller.java").apply {
            writeText("package fixture; public final class Caller { public static int result() { return Constants.VALUE; } }")
        }
        val sourceDirs = listOf(appSources, callerSources)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        fun engine() = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            dbFile = File(checkout, "const_ref.db"),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            pathResolver = ConstRefRepoPathResolver(checkout),
        )
        try {
            val builder = engine()
            try {
                runBlocking { builder.scanBaseline(sourceDirs) }
            } finally {
                builder.dispose()
            }
            val incremental = engine()
            try {
                incremental.registerSourceDirs(sourceDirs)
                constants.writeText(constants.readText().replace("VALUE = 1", "VALUE = 2"))
                val changed = listOf(constants.toStdPath())
                assertTrue(incremental.analyzeOnDemand(changed).isReady)
                assertEquals(listOf(caller.toStdPath()), incremental.getEffectedFiles(changed).map { it.refFilePath })
            } finally {
                incremental.dispose()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `should detect callers through named kotlin companion and aliases`() {
        assertCompanionCallers(
            companionName = "Codes",
            changedConstant = "CMD",
            callers = mapOf(
                "NamedUser.kt" to "import com.example.Commands\nval value = Commands.Codes.CMD",
                "AliasUser.kt" to "import com.example.Commands.Codes.CMD as ALIAS\nval value = ALIAS",
                "OuterUser.kt" to "import com.example.Commands\nval value = Commands.CMD",
            ),
            unchangedCaller = "import com.example.Commands\nval value = Commands.Codes.Nested.LIMIT",
        )
    }

    @Test
    fun `should detect callers of objects nested in kotlin companions`() {
        listOf("Companion", "Codes").forEach { companionName ->
            assertCompanionCallers(
                companionName = companionName,
                changedConstant = "LIMIT",
                callers = mapOf(
                    "NestedUser.kt" to "import com.example.Commands\nval value = Commands.$companionName.Nested.LIMIT",
                    "AliasUser.kt" to "import com.example.Commands.$companionName.Nested.LIMIT as ALIAS\nval value = ALIAS",
                ),
                unchangedCaller = "import com.example.Commands\nval value = Commands.CMD",
            )
        }
    }

    private fun assertCompanionCallers(
        companionName: String,
        changedConstant: String,
        callers: Map<String, String>,
        unchangedCaller: String,
    ) {
        val rootDir = createTempDirectory("const_ref_kotlin_companion")
        File(rootDir, ".git").mkdirs()
        val declarationName = if (companionName == "Companion") "" else companionName
        val source = """
            package com.example
            interface Commands {
                companion object $declarationName {
                    const val CMD = 1
                    object Nested { const val LIMIT = 1 }
                }
            }
        """.trimIndent()
        val constantsFile = File(rootDir, "Commands.kt").apply { writeText(source) }
        val callerFiles = (callers + ("UnchangedUser.kt" to unchangedCaller)).map { (name, body) ->
            File(rootDir, name).apply { writeText("package com.example.user\n$body") }
        }
        val paths = listOf(constantsFile.toStdPath()) + callerFiles.map { it.toStdPath() }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            assertTrue(engine.analyzeOnDemand(paths).isReady)
            engine.getEffectedFiles(paths)
            engine.acknowledgeEffectedFilesAfterDeployCommit()
            constantsFile.writeText(source.replace("$changedConstant = 1", "$changedConstant = 2"))
            assertTrue(engine.analyzeOnDemand(listOf(constantsFile.toStdPath())).isReady)
            val effected = engine.getEffectedFiles(listOf(constantsFile.toStdPath()))
            val expected = callers.keys.map { File(rootDir, it).toStdPath() }.toSet()
            assertEquals("companion=$companionName, constant=$changedConstant", expected, effected.map { it.refFilePath }.toSet())
        } finally {
            engine.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should detect kotlin callers when implicit java interface constants change`() {
        val rootDir = createTempDirectory("const_ref_java_interface")
        File(rootDir, ".git").mkdirs()
        val constantsFile = File(rootDir, "Commands.java").apply {
            writeText(javaInterfaceCommandsSource(10494, 10495))
        }
        val callers = writeJavaInterfaceConstantCallers(rootDir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scheduler = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
        )
        try {
            val sourcePaths = listOf(constantsFile.toStdPath()) + callers.map { it.toStdPath() }
            assertTrue(scheduler.analyzeOnDemand(sourcePaths).isReady)
            scheduler.getEffectedFiles(sourcePaths)
            scheduler.acknowledgeEffectedFilesAfterDeployCommit()

            constantsFile.writeText(javaInterfaceCommandsSource(10498, 10499))
            assertTrue(scheduler.analyzeOnDemand(listOf(constantsFile.toStdPath())).isReady)

            val effected = scheduler.getEffectedFiles(listOf(constantsFile.toStdPath()))
            val expectedCallers = callers.filter { it.name != "UnchangedUser.kt" }.map { it.toStdPath() }.toSet()
            assertEquals(expectedCallers, effected.map { it.refFilePath }.toSet())
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    private fun javaInterfaceCommandsSource(mikeCommand: Int, audioCommand: Int): String {
        return """
            package com.example;
            public interface Commands {
                int CMD_KTV_GAME_SWITCH_ALL_MIKE_STATUS = $mikeCommand;
                int CMD_KTV_GAME_EMIT_AUDIO_RESTRICTION = $audioCommand;
                int CMD_KTV_GAME_GET_SAFE_AREA = 10486;
            }
        """.trimIndent()
    }

    private fun writeJavaInterfaceConstantCallers(rootDir: File): List<File> {
        val sources = mapOf(
            "GameWebDelegate.kt" to """
                package com.example.web
                import com.example.Commands
                fun handle(cmd: Int) = when (cmd) {
                    Commands.CMD_KTV_GAME_SWITCH_ALL_MIKE_STATUS -> "mike"
                    Commands.CMD_KTV_GAME_EMIT_AUDIO_RESTRICTION -> "audio"
                    else -> "unknown"
                }
            """,
            "GameMediaDebugDialog.kt" to """
                package com.example.dialog
                import com.example.Commands
                fun dispatch(onCommand: (Int) -> Unit) {
                    onCommand(Commands.CMD_KTV_GAME_SWITCH_ALL_MIKE_STATUS)
                    onCommand(Commands.CMD_KTV_GAME_EMIT_AUDIO_RESTRICTION)
                }
            """,
            "UnchangedUser.kt" to """
                package com.example.other
                import com.example.Commands
                val command = Commands.CMD_KTV_GAME_GET_SAFE_AREA
            """,
        )
        return sources.map { (name, source) -> File(rootDir, name).apply { writeText(source.trimIndent()) } }
    }

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
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
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
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
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
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
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
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
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
            backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
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
