package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceFileManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing rebuild stamp rebuilds database only once`() {
        val projectDir = temporaryFolder.newFolder("project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        File(sourceDir, "Source.kt").writeText("class Source")
        val helper = createDatabase(projectDir, databaseDir, sourceDir)
        val ghostFile = File(sourceDir, "Ghost.kt")
        addFile(helper, sourceDir, ghostFile)

        val firstManager = SourceFileManager(projectDir, databaseDir, logger)
        firstManager.init(listOf(sourceDir))
        assertEquals(emptyList(), firstManager.getFiles(listOf(ghostFile.name)))

        addFile(helper, sourceDir, ghostFile)
        val secondManager = SourceFileManager(projectDir, databaseDir, logger)
        secondManager.init(listOf(sourceDir))
        assertEquals(listOf(ghostFile), secondManager.getFiles(listOf(ghostFile.name)))
    }

    @Test
    fun `expired rebuild stamp triggers full rebuild`() {
        val projectDir = temporaryFolder.newFolder("expired-project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        File(sourceDir, "Source.kt").writeText("class Source")
        val helper = createDatabase(projectDir, databaseDir, sourceDir)
        val ghostFile = File(sourceDir, "Ghost.kt")
        addFile(helper, sourceDir, ghostFile)
        rebuildStampFile(databaseDir).writeText((System.currentTimeMillis() - 15 * DAY_MILLIS).toString())

        val manager = SourceFileManager(projectDir, databaseDir, logger)
        manager.init(listOf(sourceDir))

        assertEquals(emptyList(), manager.getFiles(listOf(ghostFile.name)))
    }

    @Test
    fun `invalid and future rebuild stamps trigger recovery`() {
        listOf(
            "invalid" to "broken",
            "future" to (System.currentTimeMillis() + DAY_MILLIS).toString(),
        ).forEach { (name, stampValue) ->
            val projectDir = temporaryFolder.newFolder("$name-project")
            val databaseDir = File(projectDir, "build/jugg/database")
            val sourceDir = File(projectDir, "src").apply { mkdirs() }
            File(sourceDir, "Source.kt").writeText("class Source")
            val helper = createDatabase(projectDir, databaseDir, sourceDir)
            val ghostFile = File(sourceDir, "Ghost.kt")
            addFile(helper, sourceDir, ghostFile)
            rebuildStampFile(databaseDir).writeText(stampValue)

            val manager = SourceFileManager(projectDir, databaseDir, logger)
            manager.init(listOf(sourceDir))

            assertEquals(emptyList(), manager.getFiles(listOf(ghostFile.name)))
            assertTrue(rebuildStampFile(databaseDir).readText().toLong() <= System.currentTimeMillis())
        }
    }

    @Test
    fun `incremental updates do not refresh rebuild stamp`() {
        val projectDir = temporaryFolder.newFolder("incremental-project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        File(sourceDir, "Source.kt").writeText("class Source")
        val manager = SourceFileManager(projectDir, databaseDir, logger)
        manager.init(listOf(sourceDir))
        val rebuildAt = System.currentTimeMillis() - DAY_MILLIS
        rebuildStampFile(databaseDir).writeText(rebuildAt.toString())

        manager.updateFiles(
            listOf(changedFile(sourceDir, File(sourceDir, "Changed.kt"))),
            emptyList(),
        )

        assertEquals(rebuildAt.toString(), rebuildStampFile(databaseDir).readText())
    }

    @Test
    fun `failed database recreation does not refresh rebuild stamp`() {
        val projectDir = temporaryFolder.newFolder("failed-project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        createDatabase(projectDir, databaseDir, sourceDir)
        val rebuildAt = System.currentTimeMillis() - 15 * DAY_MILLIS
        rebuildStampFile(databaseDir).writeText(rebuildAt.toString())
        File(databaseDir, "source_files.db").apply {
            delete()
            mkdirs()
            File(this, "blocker").writeText("block")
        }

        SourceFileManager(projectDir, databaseDir, logger).init(listOf(sourceDir))

        assertEquals(rebuildAt.toString(), rebuildStampFile(databaseDir).readText())
    }

    @Test
    fun `schema recreation refreshes stamp only after source index is rebuilt`() {
        val projectDir = temporaryFolder.newFolder("schema-project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        File(sourceDir, "Source.kt").writeText("class Source")
        val helper = createDatabase(projectDir, databaseDir, sourceDir)
        val ghostFile = File(sourceDir, "Ghost.kt")
        addFile(helper, sourceDir, ghostFile)
        val rebuildAt = System.currentTimeMillis() - DAY_MILLIS
        rebuildStampFile(databaseDir).writeText(rebuildAt.toString())
        DriverManager.getConnection("jdbc:sqlite:${File(databaseDir, "source_files.db").absolutePath}").use { connection ->
            connection.createStatement().use { it.executeUpdate("PRAGMA schema_version = 2;") }
        }

        val manager = SourceFileManager(projectDir, databaseDir, logger)
        manager.init(listOf(sourceDir))

        assertEquals(emptyList(), manager.getFiles(listOf(ghostFile.name)))
        assertTrue(rebuildStampFile(databaseDir).readText().toLong() > rebuildAt)
    }

    @Test
    fun `clear build creates a fresh database and rebuild stamp`() {
        val projectDir = temporaryFolder.newFolder("clear-project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val sourceDir = File(projectDir, "src").apply { mkdirs() }
        val sourceFile = File(sourceDir, "Source.kt").apply { writeText("class Source") }
        SourceFileManager(projectDir, databaseDir, logger).init(listOf(sourceDir))
        databaseDir.deleteRecursively()

        val manager = SourceFileManager(projectDir, databaseDir, logger)
        manager.init(listOf(sourceDir))

        assertEquals(listOf(sourceFile), manager.getFiles(listOf(sourceFile.name)))
        assertTrue(rebuildStampFile(databaseDir).isFile)
    }

    @Test
    fun `recreate fails when existing database path cannot be deleted`() {
        val projectDir = temporaryFolder.newFolder("delete-project")
        val databaseDir = File(projectDir, "build/jugg/database")
        val dbFile = File(databaseDir, "source_files.db").apply {
            mkdirs()
            File(this, "blocker").writeText("block")
        }
        val helper = SourceFileDatabaseSqLiteHelper(projectDir, dbFile, logger)

        assertFailsWith<IOException> { helper.recreateDatabase() }
    }

    private fun createDatabase(projectDir: File, databaseDir: File, sourceDir: File): SourceFileDatabaseSqLiteHelper {
        val helper = SourceFileDatabaseSqLiteHelper(projectDir, File(databaseDir, "source_files.db"), logger)
        helper.init()
        helper.updateSourceDirs(listOf(sourceDir))
        return helper
    }

    private fun addFile(helper: SourceFileDatabaseSqLiteHelper, sourceDir: File, file: File) {
        helper.updateFiles(listOf(changedFile(sourceDir, file)), emptyList())
    }

    private fun changedFile(sourceDir: File, file: File) = ChangedFile(
        type = CompileFile.Type.Kotlin,
        file = file,
        baseDir = sourceDir,
        module = ModuleInfo.virtualModule,
    )

    private fun rebuildStampFile(databaseDir: File) = File(databaseDir, "source_files.rebuild_at")

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
