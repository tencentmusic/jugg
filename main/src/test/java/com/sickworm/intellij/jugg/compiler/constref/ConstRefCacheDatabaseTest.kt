package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class ConstRefCacheDatabaseTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should recreate malformed database during init`() {
        val dbDir = createTempDirectory("const_ref_db_malformed")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db").apply {
            writeText("not a sqlite database")
        }

        val database = ConstRefCacheDatabase(dbFile, logger)
        database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("ok", resultSet.getString(1))
                }
                statement.executeQuery("PRAGMA schema_version").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(7, resultSet.getInt(1))
                }
            }
        }
    }

    @Test
    fun `should recreate schema 6 database after const ref rule version bump`() {
        val dbDir = createTempDirectory("const_ref_db_schema_bump")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE old_const_definitions(id INTEGER PRIMARY KEY, const_name TEXT)")
                statement.executeUpdate("INSERT INTO old_const_definitions(const_name) VALUES('STALE_PRIVATE_CONST')")
                statement.executeUpdate("PRAGMA schema_version = 6")
            }
        }

        val database = ConstRefCacheDatabase(dbFile, logger)
        database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA schema_version").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(7, resultSet.getInt(1))
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='old_const_definitions'"
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(0, resultSet.getInt(1))
                }
            }
        }
    }

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

    @Test
    fun `should batch find reusable paths by last modified`() {
        val dbDir = createTempDirectory("const_ref_db_reusable")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)
        val constantsPath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        val userPath = File(dbDir, "User.kt").apply { writeText("val value = MAX") }.toStdPath()

        val constantsLastModified = File(constantsPath).lastModified()
        val userLastModified = File(userPath).lastModified()
        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = constantsLastModified,
            checksum = 100L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = userPath,
            lastModified = userLastModified,
            checksum = 101L,
            definitions = emptyList(),
            references = listOf(
                ConstReference(
                    refFilePath = userPath,
                    defFqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                )
            ),
        )

        val reusable = database.findReusablePathsByLastModified(
            listOf(
                File(constantsPath),
                File(userPath),
            )
        )

        assertEquals(setOf(constantsPath, userPath), reusable)
    }

    @Test
    fun `should support db first definition lookup apis`() {
        val dbDir = createTempDirectory("const_ref_db_lookup_api")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val constantsPath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        val flagsPath = File(dbDir, "BuildFlags.kt").apply { writeText("const val MAX = 2") }.toStdPath()
        val helperPath = File(dbDir, "Helper.kt").apply { writeText("const val MIN = 0") }.toStdPath()

        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 100L,
            checksum = 1000L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 101L,
            checksum = 1001L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "2",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = flagsPath,
            lastModified = 102L,
            checksum = 1002L,
            definitions = listOf(
                ConstDefinition(
                    filePath = flagsPath,
                    packageName = "com.example.flags",
                    fqClassName = "com.example.flags.BuildFlags",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "3",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = helperPath,
            lastModified = 103L,
            checksum = 1003L,
            definitions = listOf(
                ConstDefinition(
                    filePath = helperPath,
                    packageName = "com.example",
                    fqClassName = "com.example.HelperKt",
                    constName = "MIN",
                    constType = "Int",
                    constValue = "0",
                )
            ),
            references = emptyList(),
        )

        val latestByFile = database.getLatestDefinitionsByFile(constantsPath)
        assertEquals(1, latestByFile.size)
        assertEquals("2", latestByFile.first().constValue)

        val byConstName = database.queryDefinitionsByConstNames(setOf("MAX"), listOf(constantsPath))
        assertEquals(
            setOf("com.example.ConstantsKt", "com.example.flags.BuildFlags"),
            byConstName.map { it.fqClassName }.toSet(),
        )

        val byClassConst = database.queryDefinitionsByClassConstKeys(
            classConstKeys = setOf("com.example.ConstantsKt" to "MAX"),
            scopeFilePaths = listOf(constantsPath),
        )
        assertEquals(1, byClassConst.size)
        assertEquals("2", byClassConst.first().constValue)

        val byPackageConst = database.queryDefinitionsByPackageConstKeys(
            packageConstKeys = setOf("com.example" to "MAX"),
            scopeFilePaths = listOf(constantsPath),
        )
        assertEquals(1, byPackageConst.size)
        assertEquals("com.example.ConstantsKt", byPackageConst.first().fqClassName)

        val classesBySimpleName = database.queryClassesBySimpleNames(
            simpleNames = setOf("ConstantsKt", "BuildFlags"),
            scopeFilePaths = listOf(constantsPath),
        )
        assertEquals(setOf("com.example.ConstantsKt"), classesBySimpleName["ConstantsKt"])
        assertEquals(setOf("com.example.flags.BuildFlags"), classesBySimpleName["BuildFlags"])
    }

    @Test
    fun `should match companion candidate references by changed definition`() {
        val dbDir = createTempDirectory("const_ref_db_candidate_lookup")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)

        val constantsPath = File(dbDir, "Config.kt").apply {
            writeText("package com.example\nclass Config { companion object { const val MAX = 1 } }")
        }.toStdPath()
        val userPath = File(dbDir, "User.kt").apply {
            writeText("package com.example.user\nimport com.example.Config.Companion.MAX\nval value = MAX")
        }.toStdPath()

        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 100L,
            checksum = 1000L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.Config",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = userPath,
            lastModified = 101L,
            checksum = 1001L,
            definitions = emptyList(),
            references = emptyList(),
            referenceCandidates = listOf(
                ConstReferenceCandidate(
                    refFilePath = userPath,
                    packageName = "com.example.user",
                    constName = "MAX",
                    ownerName = "com.example.Config.Companion",
                    ownerKind = ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT,
                )
            ),
        )

        val effected = database.getEffectedFilesByDefinitionKeys(
            definitionKeys = setOf("com.example.Config" to "MAX"),
            scopeFilePaths = listOf(constantsPath),
        )

        assertEquals(setOf(userPath), effected.map { it.refFilePath }.toSet())
    }

    @Test
    fun `should store candidate paths and strings by id schema`() {
        val dbDir = createTempDirectory("const_ref_db_compact_schema")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db")
        val database = ConstRefCacheDatabase(dbFile, logger)
        val constantsPath = File(dbDir, "src/main/Constants.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val MAX = 1")
        }.toStdPath()
        val userPath = File(dbDir, "src/main/User.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nval value = MAX")
        }.toStdPath()

        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 100L,
            checksum = 1000L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = userPath,
            lastModified = 101L,
            checksum = 1001L,
            definitions = emptyList(),
            references = emptyList(),
            referenceCandidates = listOf(
                ConstReferenceCandidate(
                    refFilePath = userPath,
                    packageName = "com.example",
                    constName = "MAX",
                    ownerName = null,
                    ownerKind = ConstReferenceOwnerKind.BARE_SAME_PACKAGE,
                )
            ),
        )
        database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(const_reference_candidates)").use { resultSet ->
                    val columns = mutableSetOf<String>()
                    while (resultSet.next()) {
                        columns += resultSet.getString("name")
                    }
                    assertTrue("candidate table should use file_id", "file_id" in columns)
                    assertTrue("candidate table should use package_id", "package_id" in columns)
                    assertTrue("candidate table should use const_name_id", "const_name_id" in columns)
                    assertFalse("candidate table should not duplicate relative_path", "relative_path" in columns)
                    assertFalse("candidate table should not duplicate repo_key", "repo_key" in columns)
                }
                statement.executeQuery("SELECT COUNT(*) FROM strings WHERE value IN ('src/main/User.kt', 'com.example', 'MAX')").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(3, resultSet.getInt(1))
                }
            }
        }
    }

    @Test
    fun `should store owner kind as integer while preserving candidate lookup`() {
        val dbDir = createTempDirectory("const_ref_db_owner_kind_code")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db")
        val database = ConstRefCacheDatabase(dbFile, logger)
        val constantsPath = File(dbDir, "Config.kt").apply {
            writeText("package com.example\nclass Config { companion object { const val MAX = 1 } }")
        }.toStdPath()
        val userPath = File(dbDir, "User.kt").apply {
            writeText("package com.example.user\nimport com.example.Config.Companion.MAX\nval value = MAX")
        }.toStdPath()

        database.upsertFileAnalysis(
            filePath = constantsPath,
            lastModified = 100L,
            checksum = 1000L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsPath,
                    packageName = "com.example",
                    fqClassName = "com.example.Config",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = userPath,
            lastModified = 101L,
            checksum = 1001L,
            definitions = emptyList(),
            references = emptyList(),
            referenceCandidates = listOf(
                ConstReferenceCandidate(
                    refFilePath = userPath,
                    packageName = "com.example.user",
                    constName = "MAX",
                    ownerName = "com.example.Config.Companion",
                    ownerKind = ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT,
                )
            ),
        )

        val effected = database.getEffectedFilesByDefinitionKeys(
            definitionKeys = setOf("com.example.Config" to "MAX"),
            scopeFilePaths = listOf(constantsPath),
        )
        assertEquals(setOf(userPath), effected.map { it.refFilePath }.toSet())
        database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT owner_kind FROM const_reference_candidates").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(1, resultSet.getInt("owner_kind"))
                }
            }
        }
    }

    @Test
    fun `should keep string id cache bounded after large batch write`() {
        val dbDir = createTempDirectory("const_ref_db_string_cache_bound")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)
        val filePath = File(dbDir, "ManyConstants.kt").apply {
            writeText("package com.example\n")
        }.toStdPath()
        val definitions = (0 until 4500).map { index ->
            ConstDefinition(
                filePath = filePath,
                packageName = "com.example.generated",
                fqClassName = "com.example.generated.ManyConstantsKt",
                constName = "CONST_$index",
                constType = "String",
                constValue = "value_$index",
            )
        }

        database.upsertBatchAnalysis(
            listOf(
                ConstRefCacheDatabase.FileAnalysisEntry(
                    filePath = filePath,
                    lastModified = 100L,
                    checksum = 1000L,
                    definitions = definitions,
                    references = emptyList(),
                    referenceCandidates = emptyList(),
                )
            )
        )

        assertTrue(cachedStringCount(database) <= 8192)
    }

    @Test
    fun `should isolate mtime map by worktree when mtime is same`() {
        val rootDir = createTempDirectory("const_ref_db_worktree_isolation")
        val commonGitDir = File(rootDir, "common.git").apply { mkdirs() }
        val worktreeA = File(rootDir, "worktree_a").apply { mkdirs() }
        val worktreeB = File(rootDir, "worktree_b").apply { mkdirs() }
        prepareWorktreeGitRef(worktreeA, commonGitDir, "a")
        prepareWorktreeGitRef(worktreeB, commonGitDir, "b")

        val constantsInA = File(worktreeA, "src/Constants.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val MAX = 1\n")
            setLastModified(100L)
        }
        val constantsInB = File(worktreeB, "src/Constants.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nconst val MAX = 2\n")
            setLastModified(100L)
        }

        val database = ConstRefCacheDatabase(File(rootDir, "const_ref_test.db"), logger)

        database.upsertFileAnalysis(
            filePath = constantsInA.toStdPath(),
            lastModified = 100L,
            checksum = 1001L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsInA.toStdPath(),
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        database.upsertFileAnalysis(
            filePath = constantsInB.toStdPath(),
            lastModified = 100L,
            checksum = 2002L,
            definitions = listOf(
                ConstDefinition(
                    filePath = constantsInB.toStdPath(),
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "2",
                )
            ),
            references = emptyList(),
        )

        assertEquals(1001L, database.getChecksumByLastModified(constantsInA.toStdPath(), 100L))
        assertEquals(2002L, database.getChecksumByLastModified(constantsInB.toStdPath(), 100L))
        assertEquals(1001L, database.getMtimeMapChecksum(constantsInA.toStdPath()))
        assertEquals(2002L, database.getMtimeMapChecksum(constantsInB.toStdPath()))
    }

    @Test
    fun `should return latest mtime map checksum for current worktree`() {
        val dbDir = createTempDirectory("const_ref_db_mtime_baseline")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)
        val filePath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()

        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 100L,
            checksum = 111L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "1",
                )
            ),
            references = emptyList(),
        )
        assertEquals(111L, database.getMtimeMapChecksum(filePath))

        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 200L,
            checksum = 222L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                    constType = "Int",
                    constValue = "2",
                )
            ),
            references = emptyList(),
        )
        assertEquals(222L, database.getMtimeMapChecksum(filePath))

        val fileCache = database.getFileCache(filePath)
        assertNotNull(fileCache)
        assertEquals(200L, fileCache?.lastModified)
    }

    @Test
    fun `should reopen shared connection after close`() {
        val dbDir = createTempDirectory("const_ref_db_reopen_after_close")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "const_ref_test.db"), logger)
        val filePath = File(dbDir, "Constants.kt").apply { writeText("const val MAX = 1") }.toStdPath()
        val definition = ConstDefinition(
            filePath = filePath,
            packageName = "com.example",
            fqClassName = "com.example.ConstantsKt",
            constName = "MAX",
            constType = "Int",
            constValue = "1",
        )
        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 100L,
            checksum = 200L,
            definitions = listOf(definition),
            references = emptyList(),
        )

        database.close()

        val latestDefinitions = database.getLatestDefinitionsByFile(filePath)
        assertEquals(1, latestDefinitions.size)
        assertEquals("MAX", latestDefinitions.first().constName)
    }

    @Test
    fun `same db path write entries should share process write lock`() {
        val dbDir = createTempDirectory("const_ref_db_same_write_lock")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db")
        val databaseA = ConstRefCacheDatabase(dbFile, logger)
        val databaseB = ConstRefCacheDatabase(dbFile, logger)
        try {
            assertSame(databaseWriteLock(databaseA), databaseWriteLock(databaseB))
        } finally {
            databaseA.close()
            databaseB.close()
        }
    }

    @Test
    fun `different db path write entries should not share process write lock`() {
        val dbDirA = createTempDirectory("const_ref_db_write_lock_a")
        val dbDirB = createTempDirectory("const_ref_db_write_lock_b")
        File(dbDirA, ".git").mkdirs()
        File(dbDirB, ".git").mkdirs()
        val databaseA = ConstRefCacheDatabase(File(dbDirA, "const_ref_test.db"), logger)
        val databaseB = ConstRefCacheDatabase(File(dbDirB, "const_ref_test.db"), logger)
        try {
            assertNotSame(databaseWriteLock(databaseA), databaseWriteLock(databaseB))
        } finally {
            databaseA.close()
            databaseB.close()
        }
    }

    @Test
    fun `same db path public write should wait for process write lock`() {
        val dbDir = createTempDirectory("const_ref_db_write_lock_wait")
        File(dbDir, ".git").mkdirs()
        val dbFile = File(dbDir, "const_ref_test.db")
        val databaseA = ConstRefCacheDatabase(dbFile, logger)
        val databaseB = ConstRefCacheDatabase(dbFile, logger)
        val sourceDir = File(dbDir, "src").apply { mkdirs() }
        val writeLock = databaseWriteLock(databaseA)
        val lockReady = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = Thread {
            synchronized(writeLock) {
                lockReady.countDown()
                releaseLock.await(2, TimeUnit.SECONDS)
            }
        }
        lockHolder.start()
        try {
            assertTrue("write lock should be held before public write", lockReady.await(2, TimeUnit.SECONDS))
            val releaseThread = Thread {
                Thread.sleep(250L)
                releaseLock.countDown()
            }
            releaseThread.start()
            val elapsedMs = measureTimeMillis {
                databaseB.removeFilesByPrefix(sourceDir.absolutePath)
            }
            releaseThread.join(2_000L)
            assertTrue("same db path public write should wait for shared lock, elapsedMs=$elapsedMs", elapsedMs >= 200L)
        } finally {
            releaseLock.countDown()
            lockHolder.join(2_000L)
            databaseA.close()
            databaseB.close()
        }
    }

    private fun prepareWorktreeGitRef(worktreeDir: File, commonGitDir: File, worktreeName: String) {
        val worktreeGitDir = File(commonGitDir, "worktrees/$worktreeName").apply { mkdirs() }
        File(worktreeGitDir, "commondir").writeText("../../\n")
        File(worktreeDir, ".git").writeText("gitdir: ${worktreeGitDir.absolutePath}\n")
    }

    private fun cachedStringCount(database: ConstRefCacheDatabase): Int {
        val field = ConstRefCacheDatabase::class.java.getDeclaredField("stringIdCache")
        field.isAccessible = true
        val cache = field.get(database) as Map<*, *>
        return cache.size
    }

    private fun databaseWriteLock(database: ConstRefCacheDatabase): Any {
        val field = ConstRefCacheDatabase::class.java.getDeclaredField("dbWriteLock")
        field.isAccessible = true
        return field.get(database)
    }

    // ---- simple_class_name index tests ----

    @Test
    fun `queryClassesBySimpleNames should find outer class by simple name`() {
        val dbDir = createTempDirectory("const_ref_simple_name")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "test.db"), logger)

        val filePath = File(dbDir, "Constants.java").apply { writeText("class Constants {}") }.toStdPath()
        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 1L,
            checksum = 10L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.Constants",
                    constName = "MAX",
                    constType = "int",
                    constValue = "10",
                )
            ),
            references = emptyList(),
        )

        val result = database.queryClassesBySimpleNames(setOf("Constants"), listOf(filePath))
        assertEquals(setOf("com.example.Constants"), result["Constants"])
    }

    @Test
    fun `queryClassesBySimpleNames should find outer class for nested inner class`() {
        val dbDir = createTempDirectory("const_ref_inner_simple_name")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "test.db"), logger)

        val filePath = File(dbDir, "Outer.java").apply { writeText("class Outer {}") }.toStdPath()
        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 1L,
            checksum = 10L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.Outer.Inner",
                    constName = "CODE",
                    constType = "int",
                    constValue = "42",
                )
            ),
            references = emptyList(),
        )

        // Import is "import com.example.Outer" -> simpleName="Outer"
        val result = database.queryClassesBySimpleNames(setOf("Outer"), listOf(filePath))
        assertEquals(setOf("com.example.Outer.Inner"), result["Outer"])
    }

    @Test
    fun `queryClassesBySimpleNames should not return unrelated classes`() {
        val dbDir = createTempDirectory("const_ref_unrelated_simple_name")
        File(dbDir, ".git").mkdirs()
        val database = ConstRefCacheDatabase(File(dbDir, "test.db"), logger)

        val filePath = File(dbDir, "Constants.java").apply { writeText("") }.toStdPath()
        database.upsertFileAnalysis(
            filePath = filePath,
            lastModified = 1L,
            checksum = 10L,
            definitions = listOf(
                ConstDefinition(
                    filePath = filePath,
                    packageName = "com.example",
                    fqClassName = "com.example.Constants",
                    constName = "MAX",
                    constType = "int",
                    constValue = "10",
                )
            ),
            references = emptyList(),
        )

        val result = database.queryClassesBySimpleNames(setOf("Unrelated"), listOf(filePath))
        assertTrue(result.isEmpty())
    }
}
