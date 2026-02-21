package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConstRefSchedulerTest {
    @Test
    fun `should analyze pending files and return effected files`() {
        val rootDir = Files.createTempDirectory("const_ref_scheduler").toFile()
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
        val scheduler = ConstRefScheduler(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            coroutineScope = scope,
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.onFileSaved(adminFile.absolutePath)
            scheduler.awaitAnalysis(
                listOf(constantsFile.absolutePath, userFile.absolutePath, adminFile.absolutePath),
                timeoutMs = 10_000L,
            )

            val effected = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            val effectedPaths = effected.map { it.refFilePath }.toSet()
            assertEquals(setOf(userFile.toStdPath(), adminFile.toStdPath()), effectedPaths)
        } finally {
            scheduler.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `should cleanup deleted file references`() {
        val rootDir = Files.createTempDirectory("const_ref_scheduler_delete").toFile()
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
        val scheduler = ConstRefScheduler(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            coroutineScope = scope,
        )
        try {
            scheduler.onFileSaved(constantsFile.absolutePath)
            scheduler.onFileSaved(userFile.absolutePath)
            scheduler.awaitAnalysis(listOf(constantsFile.absolutePath, userFile.absolutePath), timeoutMs = 10_000L)
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
        val rootDir = Files.createTempDirectory("const_ref_scheduler_downgrade").toFile()
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
        val scheduler = ConstRefScheduler(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger),
            logger = logger,
            coroutineScope = scope,
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
}
