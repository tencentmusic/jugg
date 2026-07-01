package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.SqLiteDriverLoader
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Persist and query const-ref analysis snapshots in a repo-relative/global sqlite database.
 */
class ConstRefCacheDatabase(
    private val dbFile: File,
    private val logger: Logger,
) {
    private val maxDefinitionKeysPerQuery = 400
    private val url = "jdbc:sqlite:${dbFile.absolutePath}"
    private val dbWriteLock = ConstRefDbWriteLockRegistry.lockFor(dbFile)
    private var sharedConnection: Connection? = null
    private val repoRootByKey = mutableMapOf<String, String>()
    private val worktreeRootByKey = mutableMapOf<String, String>()
    private val stringIdCache = object : LinkedHashMap<String, Long>(STRING_ID_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > STRING_ID_CACHE_MAX_ENTRIES
        }
    }

    init {
        init()
    }

    @Synchronized
    fun init() {
        withDbWriteLock {
            SqLiteDriverLoader.load(logger)
            dbFile.parentFile?.mkdirs()
            try {
                initExistingDatabase()
            } catch (t: Throwable) {
                if (!isCorruptDatabaseError(t)) {
                    throw t
                }
                logger.warn("ConstRefCacheDatabase recreate db due to malformed database: dbFile=${dbFile.absolutePath}", t)
                recreateDatabase()
                runPassiveWalCheckpoint()
            }
        }
    }

    private fun initExistingDatabase() {
        ensureSharedConnectionLocked()
        var needRecreate = false
        var recreateReason = ""
        withConnection { connection ->
            val schemaVersion = readSchemaVersion(connection)
            val hasOldSchemaTable = tableExists(connection, "file_cache")
            if (schemaVersion != DB_SCHEMA_VERSION && (schemaVersion != 0 || hasOldSchemaTable)) {
                needRecreate = true
                recreateReason = "schema_version=$schemaVersion has_old_schema_table=$hasOldSchemaTable"
            } else {
                ensureSchema(connection)
            }
        }

        if (!needRecreate) {
            runPassiveWalCheckpoint()
            return
        }
        logger.warn("ConstRefCacheDatabase recreate db due to incompatible schema: $recreateReason")
        recreateDatabase()
        runPassiveWalCheckpoint()
    }

    /** Runs a non-blocking WAL checkpoint to merge pending WAL frames into the main db file. */
    @Synchronized
    fun runPassiveWalCheckpoint() {
        withWriteConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(PASSIVE)")
            }
        }
    }

    @Synchronized
    fun close() {
        closeConnectionLocked()
    }

    @Synchronized
    fun registerPathHints(filePaths: Collection<String>) {
        filePaths.forEach { filePath ->
            resolveRepoIdentity(filePath)
        }
    }

    @Synchronized
    fun getChecksumByLastModified(filePath: String, lastModified: Long): Long? {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return null
        return withConnection { connection ->
            val repoId = findStringId(connection, repoIdentity.repoKey) ?: return@withConnection null
            val worktreeId = findStringId(connection, repoIdentity.worktreeKey) ?: return@withConnection null
            val pathId = findStringId(connection, repoIdentity.relativePath) ?: return@withConnection null
            connection.prepareStatement(
                """
                SELECT m.checksum
                FROM file_checksum_mtime_map m
                INNER JOIN file_analysis_head h
                    ON h.repo_id = m.repo_id
                   AND h.path_id = m.path_id
                   AND h.checksum = m.checksum
                WHERE m.repo_id = ?
                  AND m.worktree_id = ?
                  AND m.path_id = ?
                  AND m.last_modified = ?
                  AND h.analyzed_at != $PHASE1_ANALYZED_AT_SENTINEL
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, repoId)
                statement.setLong(2, worktreeId)
                statement.setLong(3, pathId)
                statement.setLong(4, lastModified)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    return@withConnection resultSet.getLong("checksum")
                }
            }
        }
    }

    @Synchronized
    fun getMtimeMapChecksum(filePath: String): Long? {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return null
        return withConnection { connection ->
            val worktreeId = findStringId(connection, repoIdentity.worktreeKey) ?: return@withConnection null
            val pathId = findStringId(connection, repoIdentity.relativePath) ?: return@withConnection null
            connection.prepareStatement(
                """
                SELECT m.checksum
                FROM file_checksum_mtime_map m
                INNER JOIN file_analysis_head h
                    ON h.repo_id = m.repo_id
                   AND h.path_id = m.path_id
                   AND h.checksum = m.checksum
                WHERE m.worktree_id = ?
                  AND m.path_id = ?
                  AND h.analyzed_at != $PHASE1_ANALYZED_AT_SENTINEL
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, worktreeId)
                statement.setLong(2, pathId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    return@withConnection resultSet.getLong("checksum")
                }
            }
        }
    }

    @Synchronized
    fun hasFileAnalysis(filePath: String, checksum: Long): Boolean {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return false
        return withConnection { connection ->
            val repoId = findStringId(connection, repoIdentity.repoKey) ?: return@withConnection false
            val pathId = findStringId(connection, repoIdentity.relativePath) ?: return@withConnection false
            connection.prepareStatement(
                """
                SELECT 1
                FROM file_analysis_head
                WHERE repo_id = ?
                  AND path_id = ?
                  AND checksum = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, repoId)
                statement.setLong(2, pathId)
                statement.setLong(3, checksum)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                }
            }
        }
    }

    @Synchronized
    fun touchFileAnalysis(filePath: String, lastModified: Long, checksum: Long): Boolean {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return false
        val nowMs = System.currentTimeMillis()
        return withWriteConnection { connection ->
            val interner = StringInterner(connection)
            val repoId = interner.id(repoIdentity.repoKey)
            val pathId = interner.id(repoIdentity.relativePath)
            connection.autoCommit = false
            try {
                val updatedRows = connection.prepareStatement(
                    """
                    UPDATE file_analysis_head
                    SET last_access_at = ?
                    WHERE repo_id = ?
                      AND path_id = ?
                      AND checksum = ?
                      AND analyzed_at != $PHASE1_ANALYZED_AT_SENTINEL
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setLong(2, repoId)
                    statement.setLong(3, pathId)
                    statement.setLong(4, checksum)
                    statement.executeUpdate()
                }
                if (updatedRows <= 0) {
                    connection.rollback()
                    return@withWriteConnection false
                }

                upsertMtimeMap(
                    connection = connection,
                    interner = interner,
                    worktreeKey = repoIdentity.worktreeKey,
                    repoKey = repoIdentity.repoKey,
                    relativePath = repoIdentity.relativePath,
                    lastModified = lastModified,
                    checksum = checksum,
                    updatedAt = nowMs,
                )
                connection.commit()
                true
            } catch (t: Throwable) {
                connection.rollback()
                stringIdCache.clear()
                throw t
            } finally {
                interner.close()
                connection.autoCommit = true
            }
        }
    }

    @Synchronized
    fun getFileCache(filePath: String): FileCacheEntry? {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return null
        return withConnection { connection ->
            val repoId = findStringId(connection, repoIdentity.repoKey) ?: return@withConnection null
            val worktreeId = findStringId(connection, repoIdentity.worktreeKey) ?: return@withConnection null
            val pathId = findStringId(connection, repoIdentity.relativePath) ?: return@withConnection null
            connection.prepareStatement(
                """
                SELECT m.last_modified, m.checksum, h.analyzed_at
                FROM file_checksum_mtime_map m
                INNER JOIN file_analysis_head h
                    ON h.repo_id = m.repo_id
                   AND h.path_id = m.path_id
                   AND h.checksum = m.checksum
                WHERE m.repo_id = ?
                  AND m.worktree_id = ?
                  AND m.path_id = ?
                ORDER BY m.updated_at DESC, m.last_modified DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, repoId)
                statement.setLong(2, worktreeId)
                statement.setLong(3, pathId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    return@withConnection FileCacheEntry(
                        filePath = filePath,
                        lastModified = resultSet.getLong("last_modified"),
                        checksum = resultSet.getLong("checksum"),
                        analyzedAt = resultSet.getLong("analyzed_at"),
                    )
                }
            }
        }
    }

    @Synchronized
    fun upsertFileAnalysis(
        filePath: String,
        lastModified: Long,
        checksum: Long,
        definitions: List<ConstDefinition>,
        references: List<ConstReference>,
        referenceCandidates: List<ConstReferenceCandidate> = emptyList(),
    ) {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return
        val nowMs = System.currentTimeMillis()
        withWriteTransaction(clearStringCacheOnFailure = true) { connection ->
            val interner = StringInterner(connection)
            try {
                interner.prewarm(collectStrings(repoIdentity, definitions, references, referenceCandidates))
                val fileId = upsertAnalysisHead(connection, interner, repoIdentity, checksum, nowMs, nowMs)

                connection.prepareStatement(
                    """
                    DELETE FROM const_definitions
                    WHERE file_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, fileId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    DELETE FROM const_references
                    WHERE file_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, fileId)
                    statement.executeUpdate()
                }
                deleteReferenceCandidates(connection, fileId)

                connection.prepareStatement(
                    """
                    INSERT INTO const_definitions(
                        file_id, package_id, fq_class_id, simple_class_id, const_name_id, const_type_id, const_value_id
                    )
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    definitions.forEach { definition ->
                        statement.setLong(1, fileId)
                        statement.setLong(2, interner.id(definition.packageName))
                        statement.setLong(3, interner.id(definition.fqClassName))
                        statement.setLong(4, interner.id(extractSimpleClassName(definition.packageName, definition.fqClassName)))
                        statement.setLong(5, interner.id(definition.constName))
                        statement.setLong(6, interner.id(definition.constType))
                        setNullableLong(statement, 7, interner.idOrNull(definition.constValue))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO const_references(file_id, def_fq_class_id, const_name_id)
                    VALUES(?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    references.forEach { reference ->
                        statement.setLong(1, fileId)
                        statement.setLong(2, interner.id(reference.defFqClassName))
                        statement.setLong(3, interner.id(reference.constName))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                insertReferenceCandidates(
                    connection = connection,
                    interner = interner,
                    fileId = fileId,
                    candidates = referenceCandidates,
                )

                upsertMtimeMap(
                    connection = connection,
                    interner = interner,
                    worktreeKey = repoIdentity.worktreeKey,
                    repoKey = repoIdentity.repoKey,
                    relativePath = repoIdentity.relativePath,
                    lastModified = lastModified,
                    checksum = checksum,
                    updatedAt = nowMs,
                )
            } finally {
                interner.close()
            }
        }
    }

    /**
     * Batch-writes only definitions for a set of files in a single transaction (Phase 1).
     * Inserts a placeholder file_analysis_head row with analyzed_at=PHASE1_ANALYZED_AT_SENTINEL
     * to satisfy FK constraints; touchFileAnalysis excludes sentinel rows, so these files are
     * not treated as fully analyzed until Phase 2 overwrites the row with a real analyzed_at.
     */
    @Synchronized
    fun upsertBatchDefinitions(batch: List<FileDefinitionsEntry>) {
        if (batch.isEmpty()) {
            return
        }
        val resolvedBatch = batch.mapNotNull { entry ->
            val repoIdentity = resolveRepoIdentity(entry.filePath) ?: return@mapNotNull null
            repoIdentity to entry
        }
        if (resolvedBatch.isEmpty()) {
            return
        }
        val nowMs = System.currentTimeMillis()
        withWriteTransaction(clearStringCacheOnFailure = true) { connection ->
            val interner = StringInterner(connection)
            try {
                interner.prewarm(collectBatchDefinitionStrings(resolvedBatch))
                resolvedBatch.forEach { (repoIdentity, entry) ->
                    // Ensure file_analysis_head row exists for FK constraint.
                    // INSERT OR IGNORE preserves any existing real analyzed_at (from prior analysis).
                    // If the row already exists (real or sentinel), this is a no-op.
                    val fileId = insertAnalysisHeadIfAbsent(
                        connection = connection,
                        interner = interner,
                        repoIdentity = repoIdentity,
                        checksum = entry.checksum,
                        analyzedAt = PHASE1_ANALYZED_AT_SENTINEL,
                        lastAccessAt = nowMs,
                    )
                    connection.prepareStatement(
                        """
                        DELETE FROM const_definitions
                        WHERE file_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, fileId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO const_definitions(
                            file_id, package_id, fq_class_id, simple_class_id, const_name_id, const_type_id, const_value_id
                        )
                        VALUES(?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        entry.definitions.forEach { definition ->
                            statement.setLong(1, fileId)
                            statement.setLong(2, interner.id(definition.packageName))
                            statement.setLong(3, interner.id(definition.fqClassName))
                            statement.setLong(4, interner.id(extractSimpleClassName(definition.packageName, definition.fqClassName)))
                            statement.setLong(5, interner.id(definition.constName))
                            statement.setLong(6, interner.id(definition.constType))
                            setNullableLong(statement, 7, interner.idOrNull(definition.constValue))
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    upsertMtimeMap(
                        connection = connection,
                        interner = interner,
                        worktreeKey = repoIdentity.worktreeKey,
                        repoKey = repoIdentity.repoKey,
                        relativePath = repoIdentity.relativePath,
                        lastModified = entry.lastModified,
                        checksum = entry.checksum,
                        updatedAt = nowMs,
                    )
                }
            } finally {
                interner.close()
            }
        }
    }

    /**
     * Batch-writes full analysis results (definitions + references + file_analysis_head) in a single transaction (Phase 2).
     */
    @Synchronized
    fun upsertBatchAnalysis(batch: List<FileAnalysisEntry>) {
        if (batch.isEmpty()) {
            return
        }
        val resolvedBatch = batch.mapNotNull { entry ->
            val repoIdentity = resolveRepoIdentity(entry.filePath) ?: return@mapNotNull null
            repoIdentity to entry
        }
        if (resolvedBatch.isEmpty()) {
            return
        }
        val nowMs = System.currentTimeMillis()
        withWriteTransaction(clearStringCacheOnFailure = true) { connection ->
            val interner = StringInterner(connection)
            try {
                interner.prewarm(collectBatchAnalysisStrings(resolvedBatch))
                resolvedBatch.forEach { (repoIdentity, entry) ->
                    val fileId = upsertAnalysisHead(connection, interner, repoIdentity, entry.checksum, nowMs, nowMs)
                    connection.prepareStatement(
                        """
                        DELETE FROM const_definitions
                        WHERE file_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, fileId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        DELETE FROM const_references
                        WHERE file_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, fileId)
                        statement.executeUpdate()
                    }
                    deleteReferenceCandidates(connection, fileId)
                    connection.prepareStatement(
                        """
                        INSERT INTO const_definitions(
                            file_id, package_id, fq_class_id, simple_class_id, const_name_id, const_type_id, const_value_id
                        )
                        VALUES(?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        entry.definitions.forEach { definition ->
                            statement.setLong(1, fileId)
                            statement.setLong(2, interner.id(definition.packageName))
                            statement.setLong(3, interner.id(definition.fqClassName))
                            statement.setLong(4, interner.id(extractSimpleClassName(definition.packageName, definition.fqClassName)))
                            statement.setLong(5, interner.id(definition.constName))
                            statement.setLong(6, interner.id(definition.constType))
                            setNullableLong(statement, 7, interner.idOrNull(definition.constValue))
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO const_references(file_id, def_fq_class_id, const_name_id)
                        VALUES(?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        entry.references.forEach { reference ->
                            statement.setLong(1, fileId)
                            statement.setLong(2, interner.id(reference.defFqClassName))
                            statement.setLong(3, interner.id(reference.constName))
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    insertReferenceCandidates(
                        connection = connection,
                        interner = interner,
                        fileId = fileId,
                        candidates = entry.referenceCandidates,
                    )
                    upsertMtimeMap(
                        connection = connection,
                        interner = interner,
                        worktreeKey = repoIdentity.worktreeKey,
                        repoKey = repoIdentity.repoKey,
                        relativePath = repoIdentity.relativePath,
                        lastModified = entry.lastModified,
                        checksum = entry.checksum,
                        updatedAt = nowMs,
                    )
                }
            } finally {
                interner.close()
            }
        }
    }

    private fun deleteReferenceCandidates(
        connection: Connection,
        fileId: Long,
    ) {
        connection.prepareStatement(
            """
            DELETE FROM const_reference_candidates
            WHERE file_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, fileId)
            statement.executeUpdate()
        }
    }

    private fun insertReferenceCandidates(
        connection: Connection,
        interner: StringInterner,
        fileId: Long,
        candidates: List<ConstReferenceCandidate>,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO const_reference_candidates(
                file_id, package_id, const_name_id, owner_name_id, owner_kind, import_packages_id
            )
            VALUES(?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            candidates.forEach { candidate ->
                statement.setLong(1, fileId)
                statement.setLong(2, interner.id(candidate.packageName))
                statement.setLong(3, interner.id(candidate.constName))
                setNullableLong(statement, 4, interner.idOrNull(candidate.ownerName))
                statement.setInt(5, candidate.ownerKind.toDbCode())
                setNullableLong(statement, 6, interner.idOrNull(encodeStringSet(candidate.importPackages).ifBlank { null }))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    @Synchronized
    fun updateFileLastModified(filePath: String, lastModified: Long) {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return
        val nowMs = System.currentTimeMillis()
        withWriteTransaction(clearStringCacheOnFailure = true) { connection ->
            val checksum = queryLatestChecksum(connection, repoIdentity.repoKey, repoIdentity.relativePath) ?: return@withWriteTransaction
            val interner = StringInterner(connection)
            val repoId = interner.id(repoIdentity.repoKey)
            val pathId = interner.id(repoIdentity.relativePath)
            try {
                upsertMtimeMap(
                    connection = connection,
                    interner = interner,
                    worktreeKey = repoIdentity.worktreeKey,
                    repoKey = repoIdentity.repoKey,
                    relativePath = repoIdentity.relativePath,
                    lastModified = lastModified,
                    checksum = checksum,
                    updatedAt = nowMs,
                )
                connection.prepareStatement(
                    """
                    UPDATE file_analysis_head
                    SET last_access_at = ?
                    WHERE repo_id = ?
                      AND path_id = ?
                      AND checksum = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setLong(2, repoId)
                    statement.setLong(3, pathId)
                    statement.setLong(4, checksum)
                    statement.executeUpdate()
                }
            } finally {
                interner.close()
            }
        }
    }

    @Synchronized
    fun removeFile(filePath: String) {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return
        withWriteTransaction { connection ->
            val worktreeId = findStringId(connection, repoIdentity.worktreeKey) ?: return@withWriteTransaction
            val repoId = findStringId(connection, repoIdentity.repoKey) ?: return@withWriteTransaction
            val pathId = findStringId(connection, repoIdentity.relativePath) ?: return@withWriteTransaction
            connection.prepareStatement(
                """
                DELETE FROM file_checksum_mtime_map
                WHERE worktree_id = ?
                  AND path_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, worktreeId)
                statement.setLong(2, pathId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                DELETE FROM file_analysis_head
                WHERE repo_id = ?
                  AND path_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, repoId)
                statement.setLong(2, pathId)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun removeFilesByPrefix(prefixPath: String) {
        val repoIdentity = resolveRepoIdentity(prefixPath.removeSuffix("/")) ?: return
        val relativePath = repoIdentity.relativePath.trim('/')
        withWriteTransaction { connection ->
            val worktreeId = findStringId(connection, repoIdentity.worktreeKey) ?: return@withWriteTransaction
            val repoId = findStringId(connection, repoIdentity.repoKey) ?: return@withWriteTransaction
            if (relativePath.isBlank()) {
                connection.prepareStatement(
                    "DELETE FROM file_checksum_mtime_map WHERE worktree_id = ?"
                ).use { statement ->
                    statement.setLong(1, worktreeId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM file_analysis_head WHERE repo_id = ?"
                ).use { statement ->
                    statement.setLong(1, repoId)
                    statement.executeUpdate()
                }
            } else {
                val likePattern = "$relativePath/%"
                connection.prepareStatement(
                    """
                    DELETE FROM file_checksum_mtime_map
                    WHERE worktree_id = ?
                      AND path_id IN (
                          SELECT id FROM strings WHERE value = ? OR value LIKE ?
                      )
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, worktreeId)
                    statement.setString(2, relativePath)
                    statement.setString(3, likePattern)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    DELETE FROM file_analysis_head
                    WHERE repo_id = ?
                      AND path_id IN (
                          SELECT id FROM strings WHERE value = ? OR value LIKE ?
                      )
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, repoId)
                    statement.setString(2, relativePath)
                    statement.setString(3, likePattern)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Synchronized
    fun getAllDefinitions(excludeFilePaths: Set<String> = emptySet()): List<ConstDefinition> {
        registerPathHints(excludeFilePaths)
        val excludedIdentityKeys = excludeFilePaths
            .mapNotNull { resolveRepoIdentity(it) }
            .map { it.repoKey to it.relativePath }
            .toSet()
        val repoRoots = repoRootByKey.toMap()
        if (repoRoots.isEmpty()) {
            return emptyList()
        }
        val repoKeys = repoRoots.keys.toList()
        return withConnection { connection ->
            val repoIds = loadStringIds(connection, repoKeys).values.toList()
            if (repoIds.isEmpty()) {
                return@withConnection emptyList()
            }
            val repoPlaceholders = repoIds.joinToString(",") { "?" }
            val sql = """
                WITH latest AS (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY repo_id, path_id
                               ORDER BY analyzed_at DESC, last_access_at DESC, checksum DESC
                           ) AS rank_num
                    FROM file_analysis_head
                    WHERE repo_id IN ($repoPlaceholders)
                )
                SELECT repo.value AS repo_key,
                       path.value AS relative_path,
                       pkg.value AS package_name,
                       fq_class.value AS fq_class_name,
                       const_name.value AS const_name,
                       const_type.value AS const_type,
                       const_value.value AS const_value
                FROM const_definitions d
                INNER JOIN latest l
                    ON l.id = d.file_id
                INNER JOIN file_analysis_head h ON h.id = d.file_id
                INNER JOIN strings repo ON repo.id = h.repo_id
                INNER JOIN strings path ON path.id = h.path_id
                INNER JOIN strings pkg ON pkg.id = d.package_id
                INNER JOIN strings fq_class ON fq_class.id = d.fq_class_id
                INNER JOIN strings const_name ON const_name.id = d.const_name_id
                INNER JOIN strings const_type ON const_type.id = d.const_type_id
                LEFT JOIN strings const_value ON const_value.id = d.const_value_id
                WHERE l.rank_num = 1
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                repoIds.forEachIndexed { index, repoId ->
                    statement.setLong(index + 1, repoId)
                }
                statement.executeQuery().use { resultSet ->
                    buildDefinitions(resultSet, repoRoots, excludedIdentityKeys)
                }
            }
        }
    }

    @Synchronized
    fun getDefinitionsByFiles(filePaths: Collection<String>): List<ConstDefinition> {
        val identities = filePaths
            .mapNotNull { resolveRepoIdentity(it) }
            .distinctBy { "${it.repoKey}|${it.relativePath}" }
        if (identities.isEmpty()) {
            return emptyList()
        }
        return loadLatestDefinitionsByIdentities(identities)
    }

    @Synchronized
    fun getLatestDefinitionsByFile(filePath: String): List<ConstDefinition> {
        return getDefinitionsByFiles(listOf(filePath))
    }

    /**
     * Loads definitions for [filePath] pinned to a specific [checksum] version.
     * Used when analysis-reuse hits a previously cached checksum that may not be the "latest"
     * version in the DB (e.g. A→B→A where B is newer by analyzed_at but A is the current content).
     */
    @Synchronized
    fun getDefinitionsByFileAndChecksum(filePath: String, checksum: Long): List<ConstDefinition> {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return emptyList()
        return withConnection { connection ->
            val fileId = queryAnalysisHeadId(connection, repoIdentity, checksum) ?: return@withConnection emptyList()
            val definitions = mutableListOf<ConstDefinition>()
            connection.prepareStatement(
                """
                SELECT pkg.value AS package_name,
                       fq_class.value AS fq_class_name,
                       const_name.value AS const_name,
                       const_type.value AS const_type,
                       const_value.value AS const_value
                FROM const_definitions
                INNER JOIN strings pkg ON pkg.id = package_id
                INNER JOIN strings fq_class ON fq_class.id = fq_class_id
                INNER JOIN strings const_name ON const_name.id = const_name_id
                INNER JOIN strings const_type ON const_type.id = const_type_id
                LEFT JOIN strings const_value ON const_value.id = const_value_id
                WHERE file_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, fileId)
                statement.executeQuery().use { resultSet ->
                    val absolutePath = repoIdentity.absolutePathInWorktree()
                    while (resultSet.next()) {
                        definitions += ConstDefinition(
                            filePath = absolutePath,
                            packageName = resultSet.getString("package_name"),
                            fqClassName = resultSet.getString("fq_class_name"),
                            constName = resultSet.getString("const_name"),
                            constType = resultSet.getString("const_type"),
                            constValue = resultSet.getString("const_value"),
                        )
                    }
                }
            }
            definitions
        }
    }

    @Synchronized
    fun queryDefinitionsByConstNames(
        constNames: Set<String>,
        scopeFilePaths: Collection<String> = emptyList(),
    ): List<ConstDefinition> {
        val normalizedNames = constNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedNames.isEmpty()) {
            return emptyList()
        }
        val scopeRepoKeys = resolveScopeRepoKeys(scopeFilePaths)
        if (scopeRepoKeys.isEmpty()) {
            return emptyList()
        }
        val definitions = linkedMapOf<String, ConstDefinition>()
        val constNameIds = withConnection { connection -> loadStringIds(connection, normalizedNames).values.toList() }
        if (constNameIds.isEmpty()) {
            return emptyList()
        }
        constNameIds.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = "d.const_name_id IN (${chunk.joinToString(",") { "?" }})"
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { constNameId ->
                    statement.setLong(paramIndex++, constNameId)
                }
                paramIndex
            }.forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    @Synchronized
    fun queryDefinitionsByClassConstKeys(
        classConstKeys: Set<Pair<String, String>>,
        scopeFilePaths: Collection<String> = emptyList(),
    ): List<ConstDefinition> {
        val normalizedKeys = classConstKeys
            .mapNotNull { (fqClassName, constName) ->
                val normalizedClass = fqClassName.trim()
                val normalizedConst = constName.trim()
                if (normalizedClass.isBlank() || normalizedConst.isBlank()) {
                    null
                } else {
                    normalizedClass to normalizedConst
                }
            }
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return emptyList()
        }
        val scopeRepoKeys = resolveScopeRepoKeys(scopeFilePaths)
        if (scopeRepoKeys.isEmpty()) {
            return emptyList()
        }
        val definitions = linkedMapOf<String, ConstDefinition>()
        val stringIds = withConnection { connection ->
            loadStringIds(connection, normalizedKeys.flatMap { listOf(it.first, it.second) }.toSet())
        }
        val normalizedIdKeys = normalizedKeys.mapNotNull { (fqClassName, constName) ->
            val classId = stringIds[fqClassName] ?: return@mapNotNull null
            val constId = stringIds[constName] ?: return@mapNotNull null
            classId to constId
        }.toSet()
        normalizedIdKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = chunk.joinToString(" OR ") {
                "(d.fq_class_id = ? AND d.const_name_id = ?)"
            }
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { (fqClassId, constNameId) ->
                    statement.setLong(paramIndex++, fqClassId)
                    statement.setLong(paramIndex++, constNameId)
                }
                paramIndex
            }.forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    @Synchronized
    fun queryDefinitionsByPackageConstKeys(
        packageConstKeys: Set<Pair<String, String>>,
        scopeFilePaths: Collection<String> = emptyList(),
    ): List<ConstDefinition> {
        val normalizedKeys = packageConstKeys
            .mapNotNull { (packageName, constName) ->
                val normalizedPackage = packageName.trim()
                val normalizedConst = constName.trim()
                if (normalizedPackage.isBlank() || normalizedConst.isBlank()) {
                    null
                } else {
                    normalizedPackage to normalizedConst
                }
            }
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return emptyList()
        }
        val scopeRepoKeys = resolveScopeRepoKeys(scopeFilePaths)
        if (scopeRepoKeys.isEmpty()) {
            return emptyList()
        }
        val definitions = linkedMapOf<String, ConstDefinition>()
        val stringIds = withConnection { connection ->
            loadStringIds(connection, normalizedKeys.flatMap { listOf(it.first, it.second) }.toSet())
        }
        val normalizedIdKeys = normalizedKeys.mapNotNull { (packageName, constName) ->
            val packageId = stringIds[packageName] ?: return@mapNotNull null
            val constId = stringIds[constName] ?: return@mapNotNull null
            packageId to constId
        }.toSet()
        normalizedIdKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = chunk.joinToString(" OR ") {
                "(d.package_id = ? AND d.const_name_id = ?)"
            }
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { (packageId, constNameId) ->
                    statement.setLong(paramIndex++, packageId)
                    statement.setLong(paramIndex++, constNameId)
                }
                paramIndex
            }.forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    @Synchronized
    fun queryClassesBySimpleNames(
        simpleNames: Set<String>,
        scopeFilePaths: Collection<String> = emptyList(),
    ): Map<String, Set<String>> {
        val normalizedSimpleNames = simpleNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedSimpleNames.isEmpty()) {
            return emptyMap()
        }
        val scopeRepoKeys = resolveScopeRepoKeys(scopeFilePaths)
        if (scopeRepoKeys.isEmpty()) {
            return emptyMap()
        }
        val classesBySimpleName = mutableMapOf<String, MutableSet<String>>()
        val simpleNameIdsByValue = withConnection { connection -> loadStringIds(connection, normalizedSimpleNames) }
        if (simpleNameIdsByValue.isEmpty()) {
            return emptyMap()
        }
        simpleNameIdsByValue.values.toList().chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = "d.simple_class_id IN (${chunk.joinToString(",") { "?" }})"
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { simpleNameId ->
                    statement.setLong(paramIndex++, simpleNameId)
                }
                paramIndex
            }.forEach { definition ->
                registerSimpleNameMappings(
                    packageName = definition.packageName,
                    fqClassName = definition.fqClassName,
                    targetSimpleNames = normalizedSimpleNames,
                    classesBySimpleName = classesBySimpleName,
                )
            }
        }
        return classesBySimpleName.mapValues { (_, value) -> value.toSet() }
    }

    @Synchronized
    fun getEffectedFiles(changedFilePaths: Collection<String>): List<EffectedConstRef> {
        val identities = changedFilePaths
            .mapNotNull { resolveRepoIdentity(it) }
            .distinctBy { "${it.repoKey}|${it.relativePath}" }
        if (identities.isEmpty()) {
            return emptyList()
        }
        val changedDefinitions = loadLatestDefinitionsByIdentities(identities)
        if (changedDefinitions.isEmpty()) {
            return emptyList()
        }
        val repoKeys = identities.map { it.repoKey }.toSet()
        return queryEffectedFilesByDefinitions(changedDefinitions, repoKeys)
    }

    @Synchronized
    fun getEffectedFilesByDefinitions(definitions: Collection<ConstDefinition>): List<EffectedConstRef> {
        if (definitions.isEmpty()) {
            return emptyList()
        }
        val repoKeys = definitions
            .mapNotNull { resolveRepoIdentity(it.filePath) }
            .map { it.repoKey }
            .toSet()
        return queryEffectedFilesByDefinitions(definitions, repoKeys)
    }

    @Synchronized
    fun getEffectedFilesByDefinitionKeys(
        definitionKeys: Collection<Pair<String, String>>,
        scopeFilePaths: Collection<String> = emptyList(),
    ): List<EffectedConstRef> {
        val uniqueDefinitionKeys = definitionKeys.toSet()
        if (uniqueDefinitionKeys.isEmpty()) {
            return emptyList()
        }
        val definitions = queryDefinitionsByClassConstKeys(uniqueDefinitionKeys, scopeFilePaths)
        val foundKeys = definitions.map { it.fqClassName to it.constName }.toSet()
        val syntheticDefinitions = (uniqueDefinitionKeys - foundKeys).map { (fqClassName, constName) ->
            syntheticDefinitionForKey(fqClassName, constName)
        }
        return queryEffectedFilesByDefinitions(definitions + syntheticDefinitions, resolveScopeRepoKeys(scopeFilePaths))
    }

    /**
     * Batch query reusable files by `(repo_key, relative_path, last_modified)` to avoid unnecessary full-scan parse.
     */
    @Synchronized
    fun findReusablePathsByLastModified(files: Collection<File>): Set<String> {
        val identities = files.asSequence()
            .mapNotNull { file ->
                val filePath = file.toStdPath()
                val repoIdentity = resolveRepoIdentity(filePath) ?: return@mapNotNull null
                ReusableFileIdentity(
                    worktreeKey = repoIdentity.worktreeKey,
                    repoKey = repoIdentity.repoKey,
                    relativePath = repoIdentity.relativePath,
                    lastModified = file.lastModified(),
                )
            }
            .distinctBy { "${it.worktreeKey}|${it.relativePath}|${it.lastModified}" }
            .toList()
        if (identities.isEmpty()) {
            return emptySet()
        }
        val worktreeRoots = worktreeRootByKey.toMap()
        return withConnection { connection ->
            val reusablePaths = linkedSetOf<String>()
            val stringIds = loadStringIds(
                connection,
                identities.flatMap { listOf(it.worktreeKey, it.relativePath) }.toSet(),
            )
            identities.chunked(MAX_MTIME_QUERY_ROWS_PER_BATCH).forEach { chunk ->
                val chunkIds = chunk.mapNotNull { identity ->
                    val worktreeId = stringIds[identity.worktreeKey] ?: return@mapNotNull null
                    val pathId = stringIds[identity.relativePath] ?: return@mapNotNull null
                    ReusableFileIdentityIds(worktreeId, pathId, identity.lastModified)
                }
                if (chunkIds.isEmpty()) {
                    return@forEach
                }
                val whereClause = chunkIds.joinToString(" OR ") {
                    "(m.worktree_id = ? AND m.path_id = ? AND m.last_modified = ?)"
                }
                val sql = """
                    SELECT worktree.value AS worktree_key,
                           path.value AS relative_path
                    FROM file_checksum_mtime_map m
                    INNER JOIN file_analysis_head h
                        ON h.repo_id = m.repo_id
                       AND h.path_id = m.path_id
                       AND h.checksum = m.checksum
                    INNER JOIN strings worktree ON worktree.id = m.worktree_id
                    INNER JOIN strings path ON path.id = m.path_id
                    WHERE $whereClause
                      AND h.analyzed_at != $PHASE1_ANALYZED_AT_SENTINEL
                    GROUP BY m.worktree_id, m.path_id
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    var paramIndex = 1
                    chunkIds.forEach { identity ->
                        statement.setLong(paramIndex++, identity.worktreeId)
                        statement.setLong(paramIndex++, identity.pathId)
                        statement.setLong(paramIndex++, identity.lastModified)
                    }
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            val worktreeKey = resultSet.getString("worktree_key")
                            val relativePath = resultSet.getString("relative_path")
                            val absolutePath = resolveWorktreeAbsolutePath(worktreeKey, relativePath, worktreeRoots)
                                ?: continue
                            reusablePaths += absolutePath
                        }
                    }
                }
            }
            reusablePaths
        }
    }

    /**
     * Execute ttl/retention cleanup with 24h throttle and optional force mode.
     */
    @Synchronized
    fun cleanupIfNeeded(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): CleanupResult {
        val cleanupStats = withWriteTransaction { connection ->
            val lastCleanupAt = readMetaLong(connection, META_LAST_CLEANUP_AT) ?: 0L
            if (!force && nowMs - lastCleanupAt < CLEANUP_INTERVAL_MS) {
                return@withWriteTransaction CleanupResult(
                    executed = false,
                    removedExpiredMtimeRows = 0,
                    removedOverflowMtimeRows = 0,
                    removedExpiredAnalysisRows = 0,
                    removedOverflowAnalysisRows = 0,
                    removedOrphanMtimeRows = 0,
                    checkpointExecuted = false,
                    vacuumExecuted = false,
                )
            }

            val removedExpiredMtimeRows = executeDelete(
                connection = connection,
                sql = "DELETE FROM file_checksum_mtime_map WHERE updated_at < ?",
                params = arrayOf(nowMs - MTIME_MAP_TTL_MS),
            )
            val removedOverflowMtimeRows = executeDelete(
                connection = connection,
                sql = """
                    DELETE FROM file_checksum_mtime_map
                    WHERE rowid IN (
                        SELECT rowid FROM (
                            SELECT rowid,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY worktree_id, path_id
                                       ORDER BY updated_at DESC, last_modified DESC
                                   ) AS rank_num
                            FROM file_checksum_mtime_map
                        ) ranked
                        WHERE ranked.rank_num > ?
                    )
                """.trimIndent(),
                params = arrayOf(MAX_MTIME_ENTRIES_PER_FILE),
            )

            val removedExpiredAnalysisRows = executeDelete(
                connection = connection,
                sql = "DELETE FROM file_analysis_head WHERE last_access_at < ?",
                params = arrayOf(nowMs - ANALYSIS_TTL_MS),
            )
            val removedOverflowAnalysisRows = executeDelete(
                connection = connection,
                sql = """
                    DELETE FROM file_analysis_head
                    WHERE rowid IN (
                        SELECT rowid FROM (
                            SELECT rowid,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY repo_id, path_id
                                       ORDER BY last_access_at DESC, analyzed_at DESC
                                   ) AS rank_num
                            FROM file_analysis_head
                        ) ranked
                        WHERE ranked.rank_num > ?
                    )
                """.trimIndent(),
                params = arrayOf(MAX_ANALYSIS_ENTRIES_PER_FILE),
            )
            val removedOrphanMtimeRows = executeDelete(
                connection = connection,
                sql = """
                    DELETE FROM file_checksum_mtime_map
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM file_analysis_head
                        WHERE file_analysis_head.repo_id = file_checksum_mtime_map.repo_id
                          AND file_analysis_head.path_id = file_checksum_mtime_map.path_id
                          AND file_analysis_head.checksum = file_checksum_mtime_map.checksum
                    )
                """.trimIndent(),
                params = emptyArray(),
            )

            writeMetaLong(connection, META_LAST_CLEANUP_AT, nowMs)
            CleanupResult(
                executed = true,
                removedExpiredMtimeRows = removedExpiredMtimeRows,
                removedOverflowMtimeRows = removedOverflowMtimeRows,
                removedExpiredAnalysisRows = removedExpiredAnalysisRows,
                removedOverflowAnalysisRows = removedOverflowAnalysisRows,
                removedOrphanMtimeRows = removedOrphanMtimeRows,
                checkpointExecuted = false,
                vacuumExecuted = false,
            )
        }
        if (!cleanupStats.executed) {
            return cleanupStats
        }

        val maintenanceStats = runCheckpointAndVacuumIfNeeded(nowMs, force)
        return cleanupStats.copy(
            checkpointExecuted = maintenanceStats.checkpointExecuted,
            vacuumExecuted = maintenanceStats.vacuumExecuted,
        )
    }

    private fun recreateDatabase() {
        closeConnectionLocked()
        stringIdCache.clear()
        deleteOrMoveAside(dbFile)
        deleteOrMoveAside(File("${dbFile.absolutePath}-wal"))
        deleteOrMoveAside(File("${dbFile.absolutePath}-shm"))
        withWriteConnection { connection ->
            ensureSchema(connection)
        }
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS strings (
                    id INTEGER PRIMARY KEY,
                    value TEXT NOT NULL UNIQUE
                );

                CREATE TABLE IF NOT EXISTS file_checksum_mtime_map (
                    worktree_id INTEGER NOT NULL,
                    repo_id INTEGER NOT NULL,
                    path_id INTEGER NOT NULL,
                    last_modified INTEGER NOT NULL,
                    checksum INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (worktree_id, path_id)
                );
                CREATE INDEX IF NOT EXISTS idx_mtime_map_updated ON file_checksum_mtime_map(updated_at);
                CREATE INDEX IF NOT EXISTS idx_mtime_map_checksum ON file_checksum_mtime_map(repo_id, path_id, checksum);

                CREATE TABLE IF NOT EXISTS file_analysis_head (
                    id INTEGER PRIMARY KEY,
                    repo_id INTEGER NOT NULL,
                    path_id INTEGER NOT NULL,
                    checksum INTEGER NOT NULL,
                    analyzed_at INTEGER NOT NULL,
                    last_access_at INTEGER NOT NULL,
                    UNIQUE (repo_id, path_id, checksum)
                );
                CREATE INDEX IF NOT EXISTS idx_analysis_head_access ON file_analysis_head(last_access_at);
                CREATE INDEX IF NOT EXISTS idx_analysis_head_repo_file ON file_analysis_head(repo_id, path_id);

                CREATE TABLE IF NOT EXISTS const_definitions (
                    id INTEGER PRIMARY KEY,
                    file_id INTEGER NOT NULL,
                    package_id INTEGER NOT NULL,
                    fq_class_id INTEGER NOT NULL,
                    simple_class_id INTEGER NOT NULL,
                    const_name_id INTEGER NOT NULL,
                    const_type_id INTEGER NOT NULL,
                    const_value_id INTEGER,
                    FOREIGN KEY (file_id)
                        REFERENCES file_analysis_head(id)
                        ON DELETE CASCADE
                );
                DROP INDEX IF EXISTS idx_const_def_unique;
                CREATE UNIQUE INDEX IF NOT EXISTS idx_const_def_unique
                    ON const_definitions(file_id, fq_class_id, const_name_id);
                CREATE INDEX IF NOT EXISTS idx_const_def_file_version
                    ON const_definitions(file_id);
                CREATE INDEX IF NOT EXISTS idx_const_def_repo_package_const
                    ON const_definitions(package_id, const_name_id, file_id);
                CREATE INDEX IF NOT EXISTS idx_const_def_repo_simple_name
                    ON const_definitions(simple_class_id, const_name_id, file_id);

                CREATE TABLE IF NOT EXISTS const_references (
                    id INTEGER PRIMARY KEY,
                    file_id INTEGER NOT NULL,
                    def_fq_class_id INTEGER NOT NULL,
                    const_name_id INTEGER NOT NULL,
                    FOREIGN KEY (file_id)
                        REFERENCES file_analysis_head(id)
                        ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_ref_repo_def_class
                    ON const_references(def_fq_class_id, const_name_id, file_id);
                CREATE INDEX IF NOT EXISTS idx_ref_file_version
                    ON const_references(file_id);

                CREATE TABLE IF NOT EXISTS const_reference_candidates (
                    id INTEGER PRIMARY KEY,
                    file_id INTEGER NOT NULL,
                    package_id INTEGER NOT NULL,
                    const_name_id INTEGER NOT NULL,
                    owner_name_id INTEGER,
                    owner_kind INTEGER NOT NULL,
                    import_packages_id INTEGER,
                    FOREIGN KEY (file_id)
                        REFERENCES file_analysis_head(id)
                        ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_ref_candidate_const
                    ON const_reference_candidates(const_name_id, file_id);
                CREATE INDEX IF NOT EXISTS idx_ref_candidate_file
                    ON const_reference_candidates(file_id);

                CREATE TABLE IF NOT EXISTS maintenance_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                PRAGMA schema_version = $DB_SCHEMA_VERSION;
                """.trimIndent()
            )
        }
    }

    private fun readSchemaVersion(connection: Connection): Int {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA schema_version").use { resultSet ->
                if (!resultSet.next()) {
                    return 0
                }
                return resultSet.getInt(1)
            }
        }
    }

    private fun deleteOrMoveAside(file: File) {
        if (!file.exists()) {
            return
        }
        if (file.delete()) {
            return
        }
        val backupFile = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
        if (file.renameTo(backupFile)) {
            logger.warn("ConstRefCacheDatabase move old db file aside, from=${file.absolutePath}, to=${backupFile.absolutePath}")
            return
        }
        throw IllegalStateException("delete old const-ref db file failed, file=${file.absolutePath}")
    }

    private fun isCorruptDatabaseError(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (message.contains("sqlite_corrupt") ||
                message.contains("sqlite_notadb") ||
                message.contains("database disk image is malformed") ||
                message.contains("file is not a database")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun tableExists(connection: Connection, tableName: String): Boolean {
        connection.prepareStatement(
            """
            SELECT 1
            FROM sqlite_master
            WHERE type = 'table'
              AND name = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { resultSet ->
                return resultSet.next()
            }
        }
    }

    private fun resolveRepoIdentity(filePath: String): RepoFileIdentity? {
        val repoIdentity = ConstRefRepoPathResolver.resolve(filePath) ?: return null
        repoRootByKey[repoIdentity.repoKey] = repoIdentity.worktreeRoot.toStdPath()
        worktreeRootByKey[repoIdentity.worktreeKey] = repoIdentity.worktreeRoot.toStdPath()
        return repoIdentity
    }

    private fun resolveScopeRepoKeys(scopeFilePaths: Collection<String>): Set<String> {
        registerPathHints(scopeFilePaths)
        val scopeRepoKeys = scopeFilePaths
            .mapNotNull { resolveRepoIdentity(it)?.repoKey }
            .toSet()
        return if (scopeRepoKeys.isNotEmpty()) {
            scopeRepoKeys
        } else {
            repoRootByKey.keys.toSet()
        }
    }

    private fun queryLatestChecksum(connection: Connection, repoKey: String, relativePath: String): Long? {
        val repoId = findStringId(connection, repoKey) ?: return null
        val pathId = findStringId(connection, relativePath) ?: return null
        connection.prepareStatement(
            """
            SELECT checksum
            FROM file_analysis_head
            WHERE repo_id = ?
              AND path_id = ?
            ORDER BY analyzed_at DESC, last_access_at DESC, checksum DESC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, repoId)
            statement.setLong(2, pathId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return resultSet.getLong("checksum")
            }
        }
    }

    private fun queryLatestDefinitionsByWhere(
        scopeRepoKeys: Set<String>,
        whereClause: String,
        bindExtraParams: (PreparedStatement, Int) -> Int,
    ): List<ConstDefinition> {
        if (scopeRepoKeys.isEmpty()) {
            return emptyList()
        }
        val repoRoots = repoRootByKey.toMap()
        return withConnection { connection ->
            val scopeRepoIds = loadStringIds(connection, scopeRepoKeys).values.toSet()
            if (scopeRepoIds.isEmpty()) {
                return@withConnection emptyList()
            }
            val sql = "${buildLatestDefinitionsSql(scopeRepoIds.size)} WHERE $whereClause"
            connection.prepareStatement(sql).use { statement ->
                var paramIndex = 1
                scopeRepoIds.forEach { repoId ->
                    statement.setLong(paramIndex++, repoId)
                }
                bindExtraParams(statement, paramIndex)
                statement.executeQuery().use { resultSet ->
                    buildDefinitions(resultSet, repoRoots, excludedIdentityKeys = emptySet())
                }
            }
        }
    }

    private fun buildLatestDefinitionsSql(scopeRepoIdCount: Int): String {
        val repoPlaceholders = List(scopeRepoIdCount) { "?" }.joinToString(",")
        return """
            WITH latest AS (
                SELECT id
                FROM (
                    SELECT id,
                           ROW_NUMBER() OVER (
                               PARTITION BY repo_id, path_id
                               ORDER BY analyzed_at DESC, last_access_at DESC, checksum DESC
                           ) AS rank_num
                    FROM file_analysis_head
                    WHERE repo_id IN ($repoPlaceholders)
                ) ranked
                WHERE rank_num = 1
            )
            SELECT repo.value AS repo_key,
                   path.value AS relative_path,
                   pkg.value AS package_name,
                   fq_class.value AS fq_class_name,
                   const_name.value AS const_name,
                   const_type.value AS const_type,
                   const_value.value AS const_value
            FROM const_definitions d
            INNER JOIN latest l
                ON l.id = d.file_id
            INNER JOIN file_analysis_head h ON h.id = d.file_id
            INNER JOIN strings repo ON repo.id = h.repo_id
            INNER JOIN strings path ON path.id = h.path_id
            INNER JOIN strings pkg ON pkg.id = d.package_id
            INNER JOIN strings fq_class ON fq_class.id = d.fq_class_id
            INNER JOIN strings const_name ON const_name.id = d.const_name_id
            INNER JOIN strings const_type ON const_type.id = d.const_type_id
            LEFT JOIN strings const_value ON const_value.id = d.const_value_id
        """.trimIndent()
    }

    private fun loadLatestDefinitionsByIdentities(identities: List<RepoFileIdentity>): List<ConstDefinition> {
        if (identities.isEmpty()) {
            return emptyList()
        }
        return withConnection { connection ->
            val definitions = mutableListOf<ConstDefinition>()
            identities.forEach { identity ->
                val checksum = queryLatestChecksum(connection, identity.repoKey, identity.relativePath) ?: return@forEach
                val fileId = queryAnalysisHeadId(connection, identity, checksum) ?: return@forEach
                connection.prepareStatement(
                    """
                    SELECT pkg.value AS package_name,
                           fq_class.value AS fq_class_name,
                           const_name.value AS const_name,
                           const_type.value AS const_type,
                           const_value.value AS const_value
                    FROM const_definitions
                    INNER JOIN strings pkg ON pkg.id = package_id
                    INNER JOIN strings fq_class ON fq_class.id = fq_class_id
                    INNER JOIN strings const_name ON const_name.id = const_name_id
                    INNER JOIN strings const_type ON const_type.id = const_type_id
                    LEFT JOIN strings const_value ON const_value.id = const_value_id
                    WHERE file_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, fileId)
                    statement.executeQuery().use { resultSet ->
                        val absolutePath = identity.absolutePathInWorktree()
                        while (resultSet.next()) {
                            definitions += ConstDefinition(
                                filePath = absolutePath,
                                packageName = resultSet.getString("package_name"),
                                fqClassName = resultSet.getString("fq_class_name"),
                                constName = resultSet.getString("const_name"),
                                constType = resultSet.getString("const_type"),
                                constValue = resultSet.getString("const_value"),
                            )
                        }
                    }
                }
            }
            definitions
        }
    }

    private fun queryEffectedFilesByDefinitions(
        definitions: Collection<ConstDefinition>,
        scopeRepoKeys: Set<String>,
    ): List<EffectedConstRef> {
        val normalizedDefinitions = definitions.distinctBy { "${it.fqClassName}|${it.constName}|${it.filePath}" }
        if (normalizedDefinitions.isEmpty()) {
            return emptyList()
        }
        val effectedSet = linkedSetOf<EffectedConstRef>()
        val definitionKeys = normalizedDefinitions.map { it.fqClassName to it.constName }.toSet()
        effectedSet += queryExactEffectedFilesByDefinitionKeys(definitionKeys, scopeRepoKeys)
        effectedSet += queryCandidateEffectedFilesByDefinitions(normalizedDefinitions, scopeRepoKeys)
        return effectedSet.toList()
    }

    private fun queryExactEffectedFilesByDefinitionKeys(
        definitionKeys: Set<Pair<String, String>>,
        scopeRepoKeys: Set<String>,
    ): List<EffectedConstRef> {
        if (definitionKeys.isEmpty()) {
            return emptyList()
        }
        val repoRoots = repoRootByKey.toMap()
        return withConnection { connection ->
            val effectedSet = linkedSetOf<EffectedConstRef>()
            val stringIds = loadStringIds(connection, definitionKeys.flatMap { listOf(it.first, it.second) }.toSet())
            val definitionIdKeys = definitionKeys.mapNotNull { (fqClassName, constName) ->
                val classId = stringIds[fqClassName] ?: return@mapNotNull null
                val constId = stringIds[constName] ?: return@mapNotNull null
                classId to constId
            }.toSet()
            val scopeRepoIds = loadStringIds(connection, scopeRepoKeys).values.toSet()
            if (scopeRepoKeys.isNotEmpty() && scopeRepoIds.isEmpty()) {
                return@withConnection emptyList()
            }
            definitionIdKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
                val whereClause = chunk.joinToString(" OR ") { "(r.def_fq_class_id = ? AND r.const_name_id = ?)" }
                val sql = buildString {
                    append(
                        """
                        SELECT repo.value AS repo_key,
                               path.value AS relative_path,
                               def_fq_class.value AS def_fq_class_name,
                               const_name.value AS const_name
                        FROM const_references r
                        INNER JOIN file_analysis_head h ON h.id = r.file_id
                        INNER JOIN strings repo ON repo.id = h.repo_id
                        INNER JOIN strings path ON path.id = h.path_id
                        INNER JOIN strings def_fq_class ON def_fq_class.id = r.def_fq_class_id
                        INNER JOIN strings const_name ON const_name.id = r.const_name_id
                        WHERE ($whereClause)
                        """.trimIndent()
                    )
                    if (scopeRepoIds.isNotEmpty()) {
                        append(" AND h.repo_id IN (${scopeRepoIds.joinToString(",") { "?" }})")
                    }
                }
                connection.prepareStatement(sql).use { statement ->
                    var paramIndex = 1
                    chunk.forEach { (fqClassId, constNameId) ->
                        statement.setLong(paramIndex++, fqClassId)
                        statement.setLong(paramIndex++, constNameId)
                    }
                    if (scopeRepoIds.isNotEmpty()) {
                        scopeRepoIds.forEach { repoId ->
                            statement.setLong(paramIndex++, repoId)
                        }
                    }

                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            val repoKey = resultSet.getString("repo_key")
                            val relativePath = resultSet.getString("relative_path")
                            val absolutePath = resolveAbsolutePath(repoKey, relativePath, repoRoots) ?: continue
                            if (!File(absolutePath).exists()) {
                                continue
                            }
                            effectedSet += EffectedConstRef(
                                refFilePath = absolutePath,
                                defFqClassName = resultSet.getString("def_fq_class_name"),
                                constName = resultSet.getString("const_name"),
                            )
                        }
                    }
                }
            }
            effectedSet.toList()
        }
    }

    private fun queryCandidateEffectedFilesByDefinitions(
        definitions: List<ConstDefinition>,
        scopeRepoKeys: Set<String>,
    ): List<EffectedConstRef> {
        val definitionsByConstName = definitions.groupBy { it.constName }
        val constNames = definitionsByConstName.keys
        if (constNames.isEmpty()) {
            return emptyList()
        }
        val repoRoots = repoRootByKey.toMap()
        return withConnection { connection ->
            val effectedSet = linkedSetOf<EffectedConstRef>()
            val constNameIdsByValue = loadStringIds(connection, constNames)
            val scopeRepoIds = loadStringIds(connection, scopeRepoKeys).values.toSet()
            if (scopeRepoKeys.isNotEmpty() && scopeRepoIds.isEmpty()) {
                return@withConnection emptyList()
            }
            constNames.mapNotNull { constNameIdsByValue[it] }.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
                val constPlaceholders = chunk.joinToString(",") { "?" }
                val sql = buildString {
                    append(
                        """
                        WITH latest AS (
                            SELECT id
                            FROM (
                                SELECT id,
                                       ROW_NUMBER() OVER (
                                           PARTITION BY repo_id, path_id
                                           ORDER BY analyzed_at DESC, last_access_at DESC, checksum DESC
                                       ) AS rank_num
                                FROM file_analysis_head
                            ) ranked
                            WHERE rank_num = 1
                        )
                        SELECT repo.value AS repo_key,
                               path.value AS relative_path,
                               pkg.value AS package_name,
                               const_name.value AS const_name,
                               owner_name.value AS owner_name,
                               c.owner_kind,
                               import_packages.value AS import_packages
                        FROM const_reference_candidates c
                        INNER JOIN latest l
                            ON l.id = c.file_id
                        INNER JOIN file_analysis_head h ON h.id = c.file_id
                        INNER JOIN strings repo ON repo.id = h.repo_id
                        INNER JOIN strings path ON path.id = h.path_id
                        INNER JOIN strings pkg ON pkg.id = c.package_id
                        INNER JOIN strings const_name ON const_name.id = c.const_name_id
                        LEFT JOIN strings owner_name ON owner_name.id = c.owner_name_id
                        LEFT JOIN strings import_packages ON import_packages.id = c.import_packages_id
                        WHERE c.const_name_id IN ($constPlaceholders)
                        """.trimIndent()
                    )
                    if (scopeRepoIds.isNotEmpty()) {
                        append(" AND h.repo_id IN (${scopeRepoIds.joinToString(",") { "?" }})")
                    }
                }
                connection.prepareStatement(sql).use { statement ->
                    var paramIndex = 1
                    chunk.forEach { constNameId ->
                        statement.setLong(paramIndex++, constNameId)
                    }
                    if (scopeRepoIds.isNotEmpty()) {
                        scopeRepoIds.forEach { repoId ->
                            statement.setLong(paramIndex++, repoId)
                        }
                    }
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            val repoKey = resultSet.getString("repo_key")
                            val relativePath = resultSet.getString("relative_path")
                            val absolutePath = resolveAbsolutePath(repoKey, relativePath, repoRoots) ?: continue
                            if (!File(absolutePath).exists()) {
                                continue
                            }
                            val ownerKind = ownerKindFromDbCode(resultSet.getInt("owner_kind")) ?: continue
                            val candidate = ConstReferenceCandidate(
                                refFilePath = absolutePath,
                                packageName = resultSet.getString("package_name"),
                                constName = resultSet.getString("const_name"),
                                ownerName = resultSet.getString("owner_name"),
                                ownerKind = ownerKind,
                                importPackages = decodeStringSet(resultSet.getString("import_packages")),
                            )
                            definitionsByConstName[candidate.constName].orEmpty().forEach { definition ->
                                if (candidate.mayReference(definition)) {
                                    effectedSet += EffectedConstRef(
                                        refFilePath = absolutePath,
                                        defFqClassName = definition.fqClassName,
                                        constName = definition.constName,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            effectedSet.toList()
        }
    }

    private fun buildDefinitions(
        resultSet: ResultSet,
        repoRoots: Map<String, String>,
        excludedIdentityKeys: Set<Pair<String, String>>,
    ): List<ConstDefinition> {
        val definitions = mutableListOf<ConstDefinition>()
        while (resultSet.next()) {
            val repoKey = resultSet.getString("repo_key")
            val relativePath = resultSet.getString("relative_path")
            if ((repoKey to relativePath) in excludedIdentityKeys) {
                continue
            }
            val absolutePath = resolveAbsolutePath(repoKey, relativePath, repoRoots) ?: continue
            if (!File(absolutePath).exists()) {
                continue
            }
            definitions += ConstDefinition(
                filePath = absolutePath,
                packageName = resultSet.getString("package_name"),
                fqClassName = resultSet.getString("fq_class_name"),
                constName = resultSet.getString("const_name"),
                constType = resultSet.getString("const_type"),
                constValue = resultSet.getString("const_value"),
            )
        }
        return definitions
    }

    /**
     * Extracts the simple class name used for DB indexing from a fully-qualified class name.
     * Matches the lookup logic in [registerSimpleNameMappings]:
     *   - "com.example.Outer"       → "Outer"
     *   - "com.example.Outer.Inner" → "Outer"  (outer class, aligns with Java import)
     *   - "com.example.Outer$Inner" → "Outer"  (dollar-separated inner class)
     *   - "" / "TopLevel"           → "TopLevel"
     */
    private fun extractSimpleClassName(packageName: String, fqClassName: String): String {
        if (fqClassName.isBlank()) return ""
        val classPart = if (packageName.isBlank()) {
            fqClassName
        } else {
            fqClassName.removePrefix("$packageName.")
        }
        if (classPart.isBlank()) return ""
        // Take the first segment (outer class name), handling both '.' and '$' separators
        return classPart.substringBefore('.').substringBefore('$')
    }

    private fun registerSimpleNameMappings(
        packageName: String,
        fqClassName: String,
        targetSimpleNames: Set<String>,
        classesBySimpleName: MutableMap<String, MutableSet<String>>,
    ) {
        if (fqClassName.isBlank()) {
            return
        }
        val classPart = if (packageName.isBlank()) {
            fqClassName
        } else {
            fqClassName.removePrefix("$packageName.")
        }
        if (classPart.isBlank()) {
            return
        }
        val candidates = linkedSetOf<String>()
        candidates += classPart
        candidates += classPart.substringAfterLast('.')
        // Add outer class name so that "import com.example.Outer" resolves to
        // "com.example.Outer.Inner" (the dot-separated nested inner class).
        candidates += classPart.substringBefore('.').substringBefore('$')
        candidates
            .filter { it in targetSimpleNames }
            .forEach { simpleName ->
                classesBySimpleName.getOrPut(simpleName) { linkedSetOf() } += fqClassName
            }
    }

    private fun resolveAbsolutePath(repoKey: String, relativePath: String, repoRoots: Map<String, String>): String? {
        val repoRoot = repoRoots[repoKey] ?: return null
        return if (relativePath.isBlank()) {
            File(repoRoot).toStdPath()
        } else {
            File(repoRoot, relativePath).toStdPath()
        }
    }

    private fun resolveWorktreeAbsolutePath(
        worktreeKey: String,
        relativePath: String,
        worktreeRoots: Map<String, String>,
    ): String? {
        val worktreeRoot = worktreeRoots[worktreeKey] ?: return null
        return if (relativePath.isBlank()) {
            File(worktreeRoot).toStdPath()
        } else {
            File(worktreeRoot, relativePath).toStdPath()
        }
    }

    private fun ConstDefinition.uniqueDefinitionKey(): String {
        return "$filePath|$fqClassName|$constName|$constType|${constValue.orEmpty()}"
    }

    private fun syntheticDefinitionForKey(fqClassName: String, constName: String): ConstDefinition {
        return ConstDefinition(
            filePath = "",
            packageName = fqClassName.substringBeforeLast('.', ""),
            fqClassName = fqClassName,
            constName = constName,
            constType = "",
            constValue = null,
        )
    }

    private fun encodeStringSet(values: Set<String>): String {
        return values.joinToString("\n")
    }

    private fun decodeStringSet(value: String?): Set<String> {
        return value
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    private fun upsertAnalysisHead(
        connection: Connection,
        interner: StringInterner,
        repoIdentity: RepoFileIdentity,
        checksum: Long,
        analyzedAt: Long,
        lastAccessAt: Long,
    ): Long {
        val repoId = interner.id(repoIdentity.repoKey)
        val pathId = interner.id(repoIdentity.relativePath)
        connection.prepareStatement(
            """
            INSERT INTO file_analysis_head(repo_id, path_id, checksum, analyzed_at, last_access_at)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(repo_id, path_id, checksum)
            DO UPDATE SET analyzed_at = excluded.analyzed_at,
                          last_access_at = excluded.last_access_at
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, repoId)
            statement.setLong(2, pathId)
            statement.setLong(3, checksum)
            statement.setLong(4, analyzedAt)
            statement.setLong(5, lastAccessAt)
            statement.executeUpdate()
        }
        return queryAnalysisHeadId(connection, repoId, pathId, checksum)
            ?: error("file_analysis_head missing after upsert")
    }

    private fun insertAnalysisHeadIfAbsent(
        connection: Connection,
        interner: StringInterner,
        repoIdentity: RepoFileIdentity,
        checksum: Long,
        analyzedAt: Long,
        lastAccessAt: Long,
    ): Long {
        val repoId = interner.id(repoIdentity.repoKey)
        val pathId = interner.id(repoIdentity.relativePath)
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO file_analysis_head(repo_id, path_id, checksum, analyzed_at, last_access_at)
            VALUES(?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, repoId)
            statement.setLong(2, pathId)
            statement.setLong(3, checksum)
            statement.setLong(4, analyzedAt)
            statement.setLong(5, lastAccessAt)
            statement.executeUpdate()
        }
        return queryAnalysisHeadId(connection, repoId, pathId, checksum)
            ?: error("file_analysis_head missing after insert")
    }

    private fun queryAnalysisHeadId(
        connection: Connection,
        repoIdentity: RepoFileIdentity,
        checksum: Long,
    ): Long? {
        val repoId = findStringId(connection, repoIdentity.repoKey) ?: return null
        val pathId = findStringId(connection, repoIdentity.relativePath) ?: return null
        return queryAnalysisHeadId(connection, repoId, pathId, checksum)
    }

    private fun queryAnalysisHeadId(
        connection: Connection,
        repoId: Long,
        pathId: Long,
        checksum: Long,
    ): Long? {
        connection.prepareStatement(
            """
            SELECT id
            FROM file_analysis_head
            WHERE repo_id = ?
              AND path_id = ?
              AND checksum = ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, repoId)
            statement.setLong(2, pathId)
            statement.setLong(3, checksum)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return resultSet.getLong("id")
            }
        }
    }

    private fun setNullableLong(statement: PreparedStatement, index: Int, value: Long?) {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER)
        } else {
            statement.setLong(index, value)
        }
    }

    private fun collectBatchDefinitionStrings(batch: List<Pair<RepoFileIdentity, FileDefinitionsEntry>>): Set<String> {
        val values = linkedSetOf<String>()
        batch.forEach { (repoIdentity, entry) ->
            values += collectStrings(
                repoIdentity = repoIdentity,
                definitions = entry.definitions,
                references = emptyList(),
                referenceCandidates = emptyList(),
            )
        }
        return values
    }

    private fun collectBatchAnalysisStrings(batch: List<Pair<RepoFileIdentity, FileAnalysisEntry>>): Set<String> {
        val values = linkedSetOf<String>()
        batch.forEach { (repoIdentity, entry) ->
            values += collectStrings(
                repoIdentity = repoIdentity,
                definitions = entry.definitions,
                references = entry.references,
                referenceCandidates = entry.referenceCandidates,
            )
        }
        return values
    }

    private fun collectStrings(
        repoIdentity: RepoFileIdentity,
        definitions: List<ConstDefinition>,
        references: List<ConstReference>,
        referenceCandidates: List<ConstReferenceCandidate>,
    ): Set<String> {
        val values = linkedSetOf(
            repoIdentity.repoKey,
            repoIdentity.worktreeKey,
            repoIdentity.relativePath,
        )
        definitions.forEach { definition ->
            values += definition.packageName
            values += definition.fqClassName
            values += extractSimpleClassName(definition.packageName, definition.fqClassName)
            values += definition.constName
            values += definition.constType
            definition.constValue?.let { values += it }
        }
        references.forEach { reference ->
            values += reference.defFqClassName
            values += reference.constName
        }
        referenceCandidates.forEach { candidate ->
            values += candidate.packageName
            values += candidate.constName
            candidate.ownerName?.let { values += it }
            encodeStringSet(candidate.importPackages).ifBlank { null }?.let { values += it }
        }
        return values
    }

    private fun findStringId(connection: Connection, value: String): Long? {
        stringIdCache[value]?.let { return it }
        connection.prepareStatement("SELECT id FROM strings WHERE value = ?").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                val id = resultSet.getLong("id")
                stringIdCache[value] = id
                return id
            }
        }
    }

    private fun loadStringIds(connection: Connection, values: Collection<String>): Map<String, Long> {
        val uniqueValues = values.toSet()
        if (uniqueValues.isEmpty()) {
            return emptyMap()
        }
        val result = mutableMapOf<String, Long>()
        uniqueValues.forEach { value ->
            stringIdCache[value]?.let { id ->
                result[value] = id
            }
        }
        val missingValues = uniqueValues - result.keys
        missingValues.chunked(MAX_STRING_QUERY_ROWS_PER_BATCH).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            connection.prepareStatement("SELECT id, value FROM strings WHERE value IN ($placeholders)").use { statement ->
                chunk.forEachIndexed { index, value ->
                    statement.setString(index + 1, value)
                }
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        val id = resultSet.getLong("id")
                        val value = resultSet.getString("value")
                        result[value] = id
                        stringIdCache[value] = id
                    }
                }
            }
        }
        return result
    }

    private inner class StringInterner(private val connection: Connection) : AutoCloseable {
        private val insertStatement = connection.prepareStatement("INSERT OR IGNORE INTO strings(value) VALUES(?)")
        private val selectStatement = connection.prepareStatement("SELECT id FROM strings WHERE value = ?")
        private val localStringIds = mutableMapOf<String, Long>()

        fun prewarm(values: Collection<String>) {
            val uniqueValues = values.toSet()
            if (uniqueValues.isEmpty()) {
                return
            }
            uniqueValues
                .filter { it !in localStringIds && it !in stringIdCache }
                .chunked(MAX_STRING_QUERY_ROWS_PER_BATCH)
                .forEach { chunk ->
                    insertStatement.clearBatch()
                    chunk.forEach { value ->
                        insertStatement.clearParameters()
                        insertStatement.setString(1, value)
                        insertStatement.addBatch()
                    }
                    insertStatement.executeBatch()
                }
            localStringIds += loadStringIds(connection, uniqueValues)
        }

        fun id(value: String): Long {
            localStringIds[value]?.let { return it }
            stringIdCache[value]?.let { return it }
            insertStatement.clearParameters()
            insertStatement.setString(1, value)
            insertStatement.executeUpdate()
            selectStatement.clearParameters()
            selectStatement.setString(1, value)
            selectStatement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    error("string id missing after insert")
                }
                val id = resultSet.getLong("id")
                localStringIds[value] = id
                stringIdCache[value] = id
                return id
            }
        }

        fun idOrNull(value: String?): Long? {
            return value?.let { id(it) }
        }

        override fun close() {
            runCatching { insertStatement.close() }
            runCatching { selectStatement.close() }
        }
    }

    private fun upsertMtimeMap(
        connection: Connection,
        interner: StringInterner,
        worktreeKey: String,
        repoKey: String,
        relativePath: String,
        lastModified: Long,
        checksum: Long,
        updatedAt: Long,
    ) {
        val worktreeId = interner.id(worktreeKey)
        val repoId = interner.id(repoKey)
        val pathId = interner.id(relativePath)
        connection.prepareStatement(
            """
            INSERT INTO file_checksum_mtime_map(worktree_id, repo_id, path_id, last_modified, checksum, updated_at)
            VALUES(?, ?, ?, ?, ?, ?)
            ON CONFLICT(worktree_id, path_id)
            DO UPDATE SET repo_id = excluded.repo_id,
                          last_modified = excluded.last_modified,
                          checksum = excluded.checksum,
                          updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, worktreeId)
            statement.setLong(2, repoId)
            statement.setLong(3, pathId)
            statement.setLong(4, lastModified)
            statement.setLong(5, checksum)
            statement.setLong(6, updatedAt)
            statement.executeUpdate()
        }
    }

    private fun runCheckpointAndVacuumIfNeeded(nowMs: Long, force: Boolean): MaintenanceResult {
        return withWriteConnection { connection ->
            val lastVacuumAt = readMetaLong(connection, META_LAST_VACUUM_AT) ?: 0L
            if (!force && nowMs - lastVacuumAt < VACUUM_INTERVAL_MS) {
                return@withWriteConnection MaintenanceResult(
                    checkpointExecuted = false,
                    vacuumExecuted = false,
                )
            }
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
            var didVacuum = false
            if (dbFile.length() >= VACUUM_TRIGGER_BYTES) {
                connection.createStatement().use { statement ->
                    statement.execute("VACUUM")
                }
                didVacuum = true
            }
            writeMetaLong(connection, META_LAST_VACUUM_AT, nowMs)
            MaintenanceResult(
                checkpointExecuted = true,
                vacuumExecuted = didVacuum,
            )
        }
    }

    private fun readMetaLong(connection: Connection, key: String): Long? {
        connection.prepareStatement(
            "SELECT value FROM maintenance_meta WHERE key = ?"
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return resultSet.getString("value")?.toLongOrNull()
            }
        }
    }

    private fun writeMetaLong(connection: Connection, key: String, value: Long) {
        connection.prepareStatement(
            """
            INSERT INTO maintenance_meta(key, value)
            VALUES(?, ?)
            ON CONFLICT(key)
            DO UPDATE SET value = excluded.value
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value.toString())
            statement.executeUpdate()
        }
    }

    private fun executeDelete(connection: Connection, sql: String, params: Array<Any>): Int {
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, param ->
                when (param) {
                    is Int -> statement.setInt(index + 1, param)
                    is Long -> statement.setLong(index + 1, param)
                    is String -> statement.setString(index + 1, param)
                    else -> statement.setObject(index + 1, param)
                }
            }
            return statement.executeUpdate()
        }
    }

    private fun applyConnectionPragmas(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA cache_size=-8000")
            statement.execute("PRAGMA temp_store=MEMORY")
            statement.execute("PRAGMA busy_timeout=5000")
        }
    }

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        return block(ensureSharedConnectionLocked())
    }

    private inline fun <T> withWriteConnection(block: (Connection) -> T): T {
        return withDbWriteLock {
            withConnection(block)
        }
    }

    private inline fun <T> withWriteTransaction(
        clearStringCacheOnFailure: Boolean = false,
        block: (Connection) -> T,
    ): T {
        return withWriteConnection { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (t: Throwable) {
                connection.rollback()
                if (clearStringCacheOnFailure) {
                    stringIdCache.clear()
                }
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private inline fun <T> withDbWriteLock(block: () -> T): T {
        synchronized(dbWriteLock) {
            return block()
        }
    }

    private fun ensureSharedConnectionLocked(): Connection {
        val existed = sharedConnection
        if (existed != null) {
            runCatching {
                if (!existed.isClosed) {
                    return existed
                }
            }.onFailure {
                logger.warn("ConstRefCacheDatabase shared connection status check failed", it)
            }
        }
        val connection = DriverManager.getConnection(url)
        try {
            applyConnectionPragmas(connection)
            sharedConnection = connection
            return connection
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
    }

    private fun closeConnectionLocked() {
        val connection = sharedConnection ?: return
        sharedConnection = null
        runCatching {
            if (!connection.isClosed) {
                connection.close()
            }
        }.onFailure {
            logger.warn("ConstRefCacheDatabase close shared connection failed", it)
        }
    }

    /**
     * Entry for batch-writing only definitions (Phase 1 of batched full-scan).
     * Does NOT write file_analysis_head, so interrupted batches leave no stale "analyzed" marker.
     */
    data class FileDefinitionsEntry(
        val filePath: String,
        val lastModified: Long,
        val checksum: Long,
        val definitions: List<ConstDefinition>,
    )

    /**
     * Entry for batch-writing full analysis results (Phase 2 of batched full-scan).
     * Writes file_analysis_head together with definitions and references in one transaction.
     */
    data class FileAnalysisEntry(
        val filePath: String,
        val lastModified: Long,
        val checksum: Long,
        val definitions: List<ConstDefinition>,
        val references: List<ConstReference>,
        val referenceCandidates: List<ConstReferenceCandidate> = emptyList(),
    )

    data class FileCacheEntry(
        val filePath: String,
        val lastModified: Long,
        val checksum: Long,
        val analyzedAt: Long,
    )

    data class CleanupResult(
        val executed: Boolean,
        val removedExpiredMtimeRows: Int,
        val removedOverflowMtimeRows: Int,
        val removedExpiredAnalysisRows: Int,
        val removedOverflowAnalysisRows: Int,
        val removedOrphanMtimeRows: Int,
        val checkpointExecuted: Boolean,
        val vacuumExecuted: Boolean,
    )

    private data class MaintenanceResult(
        val checkpointExecuted: Boolean,
        val vacuumExecuted: Boolean,
    )

    private data class ReusableFileIdentity(
        val worktreeKey: String,
        val repoKey: String,
        val relativePath: String,
        val lastModified: Long,
    )

    private data class ReusableFileIdentityIds(
        val worktreeId: Long,
        val pathId: Long,
        val lastModified: Long,
    )

    private fun ConstReferenceOwnerKind.toDbCode(): Int {
        return when (this) {
            ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT -> 1
            ConstReferenceOwnerKind.EXPLICIT_CLASS_IMPORT -> 2
            ConstReferenceOwnerKind.PACKAGE_STAR_IMPORT -> 3
            ConstReferenceOwnerKind.CLASS_STAR_IMPORT -> 4
            ConstReferenceOwnerKind.OWNER_EXPRESSION -> 5
            ConstReferenceOwnerKind.BARE_SAME_PACKAGE -> 6
        }
    }

    private fun ownerKindFromDbCode(code: Int): ConstReferenceOwnerKind? {
        return when (code) {
            1 -> ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT
            2 -> ConstReferenceOwnerKind.EXPLICIT_CLASS_IMPORT
            3 -> ConstReferenceOwnerKind.PACKAGE_STAR_IMPORT
            4 -> ConstReferenceOwnerKind.CLASS_STAR_IMPORT
            5 -> ConstReferenceOwnerKind.OWNER_EXPRESSION
            6 -> ConstReferenceOwnerKind.BARE_SAME_PACKAGE
            else -> null
        }
    }

    companion object {
        private const val DB_SCHEMA_VERSION = 7
        private const val MAX_MTIME_QUERY_ROWS_PER_BATCH = 250
        private const val MAX_STRING_QUERY_ROWS_PER_BATCH = 500
        private const val STRING_ID_CACHE_MAX_ENTRIES = 8192
        private const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val MTIME_MAP_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val ANALYSIS_TTL_MS = 90L * 24L * 60L * 60L * 1000L
        private const val MAX_MTIME_ENTRIES_PER_FILE = 20
        private const val MAX_ANALYSIS_ENTRIES_PER_FILE = 5
        private const val VACUUM_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val VACUUM_TRIGGER_BYTES = 256L * 1024L * 1024L
        private const val META_LAST_CLEANUP_AT = "last_cleanup_at"
        private const val META_LAST_VACUUM_AT = "last_vacuum_at"

        /**
         * Sentinel value written by upsertBatchDefinitions (Phase 1) to file_analysis_head.
         * Indicates definitions-only state (no references yet). touchFileAnalysis excludes rows
         * with this value so they are never treated as complete analysis.
         * Using Long.MAX_VALUE also ensures these rows rank first in analyzed_at DESC ordering,
         * making Phase 1 definitions visible to Phase 2 DB queries.
         */
        internal const val PHASE1_ANALYZED_AT_SENTINEL = Long.MAX_VALUE
    }
}

private object ConstRefDbWriteLockRegistry {
    private val locks = ConcurrentHashMap<String, Any>()

    fun lockFor(dbFile: File): Any {
        val key = runCatching { dbFile.canonicalFile.absolutePath }
            .getOrElse { dbFile.absoluteFile.absolutePath }
        return locks.getOrPut(key) { Any() }
    }
}
