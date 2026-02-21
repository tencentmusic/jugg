package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConstRefCacheDatabaseTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should query effected files by changed constant definition`() {
        val dbDir = createTempDirectory("const_ref_db")
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val constantsPath = File(dbDir, "Constants.kt").toStdPath()
        val userPath = File(dbDir, "User.kt").toStdPath()

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
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val constantsPath = File(dbDir, "Constants.kt").toStdPath()
        val anotherPath = File(dbDir, "Another.kt").toStdPath()
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
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val debugPath = File(dbDir, "debug/Constants.kt").toStdPath()
        val releasePath = File(dbDir, "release/Constants.kt").toStdPath()

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
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val filePath = File(dbDir, "Constants.kt").toStdPath()
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
}
