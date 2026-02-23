package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class ConstRefCacheDatabaseTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should query effected files by changed constant definition`() {
        val dbDir = createTempDirectory("const_ref_db")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val constantsPath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        val userPath = File(dbDir, "User.kt").apply { writeText("val value = MAX") }.toStdPath()

        val constantDefinition = ConstDefinition(
            filePath = constantsPath,
            packageName = "com.example",
            fqClassName = "com.example.ConstantsKt",
            constName = "MAX",
            constType = "Int",
            constValue = "10",
        )
        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 1L,
            checksum = 11L,
            definitions = listOf(constantDefinition),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = userPath,
            lastModified = 2L,
            checksum = 22L,
            definitions = emptyList(),
            references = listOf(
                ConstReference(
                    refFilePath = userPath,
                    defFqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                )
            ),
        )

        val effected = database.getEffectedFiles(listOf(constantsPath))
        assertEquals(1, effected.size)
        assertEquals(userPath, effected.first().refFilePath)
        assertEquals("com.example.ConstantsKt", effected.first().defFqClassName)
        assertEquals("MAX", effected.first().constName)
    }

    @Test
    fun `should exclude file definitions when requested`() {
        val dbDir = createTempDirectory("const_ref_db_exclude")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val constantsPath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        val anotherPath = File(dbDir, "Another.kt").apply { writeText("const val MIN = 1") }.toStdPath()
        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 1L,
            checksum = 11L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "10",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = anotherPath,
            lastModified = 2L,
            checksum = 22L,
            definitions = listOf(
                ConstDefinition(
                    filePath = anotherPath,
                    packageName = "com.example",
                    fqClassName = "com.example.AnotherKt",
                    constName = "MIN",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )

        val allDefinitions = database.getAllDefinitions()
        assertEquals(2, allDefinitions.size)

        val excludedDefinitions = database.getAllDefinitions(setOf(constantsPath))
        assertEquals(1, excludedDefinitions.size)
        assertTrue(excludedDefinitions.all { it.filePath == anotherPath })
    }

    @Test
    fun `should allow same class and const name from different files`() {
        val dbDir = createTempDirectory("const_ref_db_unique")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val debugPath = File(dbDir, "debug/Constants.kt").apply {
            parentFile.mkdirs()
            writeText("const val MAX = 10")
        }.toStdPath()
        val releasePath = File(dbDir, "release/Constants.kt").apply {
            parentFile.mkdirs()
            writeText("const val MAX = 20")
        }.toStdPath()

        val debugDefinition = ConstDefinition(
            filePath = debugPath,
            packageName = "com.example",
            fqClassName = "com.example.ConstantsKt",
            constName = "MAX",
            constType = "Int",
            constValue = "10",
        )
        val releaseDefinition = debugDefinition.copy(filePath = releasePath, constValue = "20")

        database.upsertFileAnalysis(
            filePath = debugPath,
            lastModified = 1L,
            checksum = 11L,
            definitions = listOf(debugDefinition),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = releasePath,
            lastModified = 2L,
            checksum = 22L,
            definitions = listOf(releaseDefinition),
            references = emptyList(),
        )

        val definitions = database.getAllDefinitions()
        assertEquals(2, definitions.size)
        assertTrue(definitions.any { it.filePath == debugPath && it.constValue == "10" })
        assertTrue(definitions.any { it.filePath == releasePath && it.constValue == "20" })
    }

    @Test
    fun `should update file last modified without changing checksum`() {
        val dbDir = createTempDirectory("const_ref_db_update_last_modified")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val filePath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 100L,
            checksum = 200L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "10",
                )
            ),
            references = emptyList(),
        )
        val before = database.getFileCache(filePath)
        assertEquals(100L, before?.lastModified)
        assertEquals(200L, before?.checksum)

        database.updateFileLastModified(filePath, 300L)

        val after = database.getFileCache(filePath)
        assertEquals(300L, after?.lastModified)
        assertEquals(200L, after?.checksum)
    }

    @Test
    fun `should reuse checksum by mtime map`() {
        val dbDir = createTempDirectory("const_ref_db_mtime_map")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val filePath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 100L,
            checksum = 200L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "10",
                )
            ),
            references = emptyList(),
        )

        assertTrue(database.touchFileAnalysis(filePath, 300L, 200L))
        assertEquals(200L, database.getChecksumByLastModified(filePath, 300L))
    }

    @Test
    fun `should cleanup overflow versions by retention policy`() {
        val dbDir = createTempDirectory("const_ref_db_cleanup")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db")
        val database = ConstRefCacheDatabase(dbFile, logger)
        val filePath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()

        repeat(25) { index ->
            database.upsertFileAnalysis(
                filePath = filePath,
                lastModified = index.toLong(),
                checksum = (1000 + index).toLong(),
                definitions = listOf(
                    ConstDefinition(
                        filePath = filePath,
                        packageName = "com.example",
                        fqClassName = "com.example.ConstantsKt",
                        constName = "MAX_$index",
                        constType = "Int",
                        constValue = index.toString(),
                    )
                ),
                references = emptyList(),
            )
        }

        database.cleanupIfNeeded(force = true)

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM file_checksum_mtime_map").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertTrue(resultSet.getInt(1) <= 20)
                }
                statement.executeQuery("SELECT COUNT(*) FROM file_analysis_head").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertTrue(resultSet.getInt(1) <= 5)
                }
            }
        }
    }
}
