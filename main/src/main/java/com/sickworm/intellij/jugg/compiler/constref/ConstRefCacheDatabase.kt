package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.SqLiteDriverLoader
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Persist and query const-ref analysis snapshots in a repo-relative/global sqlite database.
 */
class ConstRefCacheDatabase(
    private val dbFile: File,
    private val logger: Logger,
) {
    private val maxDefinitionKeysPerQuery = 400
    private val url = "jdbc:sqlite:${dbFile.absolutePath}"
    private val repoRootByKey = mutableMapOf<String, String>()
    private val worktreeRootByKey = mutableMapOf<String, String>()

    init {
        init()
    }

    @Synchronized
    fun init() {
        SqLiteDriverLoader.load(logger)
        dbFile.parentFile?.mkdirs()

        var needRecreate = false
        var recreateReason = ""
        withConnection { connection ->
            val schemaVersion = readSchemaVersion(connection)
            val hasLegacySchema = tableExists(connection, "file_cache")
            if (schemaVersion != DB_SCHEMA_VERSION && (schemaVersion != 0 || hasLegacySchema)) {
                needRecreate = true
                recreateReason = "schema_version=$schemaVersion legacy=$hasLegacySchema"
            } else {
                ensureSchema(connection)
            }
        }

        if (!needRecreate) {
            return
        }
        logger.warn("ConstRefCacheDatabase recreate db due to incompatible schema: $recreateReason")
        recreateDatabase()
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
            connection.prepareStatement(
                """
                SELECT m.checksum
                FROM file_checksum_mtime_map m
                INNER JOIN file_analysis_head h
                    ON h.repo_key = m.repo_key
                   AND h.relative_path = m.relative_path
                   AND h.checksum = m.checksum
                WHERE m.repo_key = ?
                  AND m.worktree_key = ?
                  AND m.relative_path = ?
                  AND m.last_modified = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoIdentity.repoKey)
                statement.setString(2, repoIdentity.worktreeKey)
                statement.setString(3, repoIdentity.relativePath)
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
            connection.prepareStatement(
                """
                SELECT m.checksum
                FROM file_checksum_mtime_map m
                INNER JOIN file_analysis_head h
                    ON h.repo_key = m.repo_key
                   AND h.relative_path = m.relative_path
                   AND h.checksum = m.checksum
                WHERE m.worktree_key = ?
                  AND m.relative_path = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoIdentity.worktreeKey)
                statement.setString(2, repoIdentity.relativePath)
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
            connection.prepareStatement(
                """
                SELECT 1
                FROM file_analysis_head
                WHERE repo_key = ?
                  AND relative_path = ?
                  AND checksum = ?
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoIdentity.repoKey)
                statement.setString(2, repoIdentity.relativePath)
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
        return withConnection { connection ->
            connection.autoCommit = false
            try {
                val updatedRows = connection.prepareStatement(
                    """
                    UPDATE file_analysis_head
                    SET last_access_at = ?
                    WHERE repo_key = ?
                      AND relative_path = ?
                      AND checksum = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setString(2, repoIdentity.repoKey)
                    statement.setString(3, repoIdentity.relativePath)
                    statement.setLong(4, checksum)
                    statement.executeUpdate()
                }
                if (updatedRows <= 0) {
                    connection.rollback()
                    return@withConnection false
                }

                upsertMtimeMap(
                    connection = connection,
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
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    @Synchronized
    fun getFileCache(filePath: String): FileCacheEntry? {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return null
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT m.last_modified, m.checksum, h.analyzed_at
                FROM file_checksum_mtime_map m
                INNER JOIN file_analysis_head h
                    ON h.repo_key = m.repo_key
                   AND h.relative_path = m.relative_path
                   AND h.checksum = m.checksum
                WHERE m.repo_key = ?
                  AND m.worktree_key = ?
                  AND m.relative_path = ?
                ORDER BY m.updated_at DESC, m.last_modified DESC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoIdentity.repoKey)
                statement.setString(2, repoIdentity.worktreeKey)
                statement.setString(3, repoIdentity.relativePath)
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
    ) {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return
        val nowMs = System.currentTimeMillis()
        withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO file_analysis_head(repo_key, relative_path, checksum, analyzed_at, last_access_at)
                    VALUES(?, ?, ?, ?, ?)
                    ON CONFLICT(repo_key, relative_path, checksum)
                    DO UPDATE SET analyzed_at = excluded.analyzed_at,
                                  last_access_at = excluded.last_access_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, repoIdentity.repoKey)
                    statement.setString(2, repoIdentity.relativePath)
                    statement.setLong(3, checksum)
                    statement.setLong(4, nowMs)
                    statement.setLong(5, nowMs)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    DELETE FROM const_definitions
                    WHERE repo_key = ?
                      AND relative_path = ?
                      AND checksum = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, repoIdentity.repoKey)
                    statement.setString(2, repoIdentity.relativePath)
                    statement.setLong(3, checksum)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    DELETE FROM const_references
                    WHERE repo_key = ?
                      AND relative_path = ?
                      AND checksum = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, repoIdentity.repoKey)
                    statement.setString(2, repoIdentity.relativePath)
                    statement.setLong(3, checksum)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO const_definitions(
                        repo_key, relative_path, checksum, package_name, fq_class_name, const_name, const_type, const_value
                    )
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    definitions.forEach { definition ->
                        statement.setString(1, repoIdentity.repoKey)
                        statement.setString(2, repoIdentity.relativePath)
                        statement.setLong(3, checksum)
                        statement.setString(4, definition.packageName)
                        statement.setString(5, definition.fqClassName)
                        statement.setString(6, definition.constName)
                        statement.setString(7, definition.constType)
                        statement.setString(8, definition.constValue)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO const_references(repo_key, relative_path, checksum, def_fq_class_name, const_name)
                    VALUES(?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    references.forEach { reference ->
                        statement.setString(1, repoIdentity.repoKey)
                        statement.setString(2, repoIdentity.relativePath)
                        statement.setLong(3, checksum)
                        statement.setString(4, reference.defFqClassName)
                        statement.setString(5, reference.constName)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                upsertMtimeMap(
                    connection = connection,
                    worktreeKey = repoIdentity.worktreeKey,
                    repoKey = repoIdentity.repoKey,
                    relativePath = repoIdentity.relativePath,
                    lastModified = lastModified,
                    checksum = checksum,
                    updatedAt = nowMs,
                )
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    @Synchronized
    fun updateFileLastModified(filePath: String, lastModified: Long) {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return
        val nowMs = System.currentTimeMillis()
        withConnection { connection ->
            val checksum = queryLatestChecksum(connection, repoIdentity.repoKey, repoIdentity.relativePath) ?: return@withConnection
            connection.autoCommit = false
            try {
                upsertMtimeMap(
                    connection = connection,
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
                    WHERE repo_key = ?
                      AND relative_path = ?
                      AND checksum = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setString(2, repoIdentity.repoKey)
                    statement.setString(3, repoIdentity.relativePath)
                    statement.setLong(4, checksum)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    @Synchronized
    fun removeFile(filePath: String) {
        val repoIdentity = resolveRepoIdentity(filePath) ?: return
        withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    DELETE FROM file_checksum_mtime_map
                    WHERE worktree_key = ?
                      AND relative_path = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, repoIdentity.worktreeKey)
                    statement.setString(2, repoIdentity.relativePath)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    DELETE FROM file_analysis_head
                    WHERE repo_key = ?
                      AND relative_path = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, repoIdentity.repoKey)
                    statement.setString(2, repoIdentity.relativePath)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    @Synchronized
    fun removeFilesByPrefix(prefixPath: String) {
        val repoIdentity = resolveRepoIdentity(prefixPath.removeSuffix("/")) ?: return
        val relativePath = repoIdentity.relativePath.trim('/')
        withConnection { connection ->
            connection.autoCommit = false
            try {
                if (relativePath.isBlank()) {
                    connection.prepareStatement(
                        "DELETE FROM file_checksum_mtime_map WHERE worktree_key = ?"
                    ).use { statement ->
                        statement.setString(1, repoIdentity.worktreeKey)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        "DELETE FROM file_analysis_head WHERE repo_key = ?"
                    ).use { statement ->
                        statement.setString(1, repoIdentity.repoKey)
                        statement.executeUpdate()
                    }
                } else {
                    val likePattern = "$relativePath/%"
                    connection.prepareStatement(
                        """
                        DELETE FROM file_checksum_mtime_map
                        WHERE worktree_key = ?
                          AND (relative_path = ? OR relative_path LIKE ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, repoIdentity.worktreeKey)
                        statement.setString(2, relativePath)
                        statement.setString(3, likePattern)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        DELETE FROM file_analysis_head
                        WHERE repo_key = ?
                          AND (relative_path = ? OR relative_path LIKE ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, repoIdentity.repoKey)
                        statement.setString(2, relativePath)
                        statement.setString(3, likePattern)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
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
            val repoPlaceholders = repoKeys.joinToString(",") { "?" }
            val sql = """
                WITH latest AS (
                    SELECT repo_key,
                           relative_path,
                           checksum,
                           ROW_NUMBER() OVER (
                               PARTITION BY repo_key, relative_path
                               ORDER BY analyzed_at DESC, last_access_at DESC
                           ) AS rank_num
                    FROM file_analysis_head
                    WHERE repo_key IN ($repoPlaceholders)
                )
                SELECT d.repo_key, d.relative_path, d.package_name, d.fq_class_name, d.const_name, d.const_type, d.const_value
                FROM const_definitions d
                INNER JOIN latest l
                    ON l.repo_key = d.repo_key
                   AND l.relative_path = d.relative_path
                   AND l.checksum = d.checksum
                WHERE l.rank_num = 1
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                repoKeys.forEachIndexed { index, repoKey ->
                    statement.setString(index + 1, repoKey)
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
            val definitions = mutableListOf<ConstDefinition>()
            connection.prepareStatement(
                """
                SELECT package_name, fq_class_name, const_name, const_type, const_value
                FROM const_definitions
                WHERE repo_key = ?
                  AND relative_path = ?
                  AND checksum = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoIdentity.repoKey)
                statement.setString(2, repoIdentity.relativePath)
                statement.setLong(3, checksum)
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
        normalizedNames.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = "d.const_name IN (${chunk.joinToString(",") { "?" }})"
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { constName ->
                    statement.setString(paramIndex++, constName)
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
        normalizedKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = chunk.joinToString(" OR ") {
                "(d.fq_class_name = ? AND d.const_name = ?)"
            }
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { (fqClassName, constName) ->
                    statement.setString(paramIndex++, fqClassName)
                    statement.setString(paramIndex++, constName)
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
        normalizedKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
            val whereClause = chunk.joinToString(" OR ") {
                "(d.package_name = ? AND d.const_name = ?)"
            }
            queryLatestDefinitionsByWhere(
                scopeRepoKeys = scopeRepoKeys,
                whereClause = whereClause,
            ) { statement, startIndex ->
                var paramIndex = startIndex
                chunk.forEach { (packageName, constName) ->
                    statement.setString(paramIndex++, packageName)
                    statement.setString(paramIndex++, constName)
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
        return withConnection { connection ->
            val sql = buildLatestDefinitionsSql(scopeRepoKeys)
            connection.prepareStatement(sql).use { statement ->
                var paramIndex = 1
                scopeRepoKeys.forEach { repoKey ->
                    statement.setString(paramIndex++, repoKey)
                }
                statement.executeQuery().use { resultSet ->
                    val classesBySimpleName = mutableMapOf<String, MutableSet<String>>()
                    while (resultSet.next()) {
                        val packageName = resultSet.getString("package_name")
                        val fqClassName = resultSet.getString("fq_class_name")
                        registerSimpleNameMappings(
                            packageName = packageName,
                            fqClassName = fqClassName,
                            targetSimpleNames = normalizedSimpleNames,
                            classesBySimpleName = classesBySimpleName,
                        )
                    }
                    classesBySimpleName.mapValues { (_, value) -> value.toSet() }
                }
            }
        }
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
        val uniqueDefinitionKeys = changedDefinitions.map { it.fqClassName to it.constName }.toSet()
        val repoKeys = identities.map { it.repoKey }.toSet()
        return queryEffectedFilesByDefinitionKeys(uniqueDefinitionKeys, repoKeys)
    }

    @Synchronized
    fun getEffectedFilesByDefinitions(definitions: Collection<ConstDefinition>): List<EffectedConstRef> {
        val uniqueDefinitionKeys = definitions.map { it.fqClassName to it.constName }.toSet()
        if (uniqueDefinitionKeys.isEmpty()) {
            return emptyList()
        }
        val repoKeys = definitions
            .mapNotNull { resolveRepoIdentity(it.filePath) }
            .map { it.repoKey }
            .toSet()
        return queryEffectedFilesByDefinitionKeys(uniqueDefinitionKeys, repoKeys)
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
        val repoKeys = scopeFilePaths
            .mapNotNull { resolveRepoIdentity(it) }
            .map { it.repoKey }
            .toSet()
        return queryEffectedFilesByDefinitionKeys(uniqueDefinitionKeys, repoKeys)
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
            identities.chunked(MAX_MTIME_QUERY_ROWS_PER_BATCH).forEach { chunk ->
                val whereClause = chunk.joinToString(" OR ") {
                    "(m.worktree_key = ? AND m.relative_path = ? AND m.last_modified = ?)"
                }
                val sql = """
                    SELECT m.worktree_key, m.relative_path
                    FROM file_checksum_mtime_map m
                    INNER JOIN file_analysis_head h
                        ON h.repo_key = m.repo_key
                       AND h.relative_path = m.relative_path
                       AND h.checksum = m.checksum
                    WHERE $whereClause
                    GROUP BY m.worktree_key, m.relative_path
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    var paramIndex = 1
                    chunk.forEach { identity ->
                        statement.setString(paramIndex++, identity.worktreeKey)
                        statement.setString(paramIndex++, identity.relativePath)
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
        val cleanupStats = withConnection { connection ->
            val lastCleanupAt = readMetaLong(connection, META_LAST_CLEANUP_AT) ?: 0L
            if (!force && nowMs - lastCleanupAt < CLEANUP_INTERVAL_MS) {
                return@withConnection CleanupResult(
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

            connection.autoCommit = false
            try {
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
                                           PARTITION BY worktree_key, relative_path
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
                                           PARTITION BY repo_key, relative_path
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
                            WHERE file_analysis_head.repo_key = file_checksum_mtime_map.repo_key
                              AND file_analysis_head.relative_path = file_checksum_mtime_map.relative_path
                              AND file_analysis_head.checksum = file_checksum_mtime_map.checksum
                        )
                    """.trimIndent(),
                    params = emptyArray(),
                )

                writeMetaLong(connection, META_LAST_CLEANUP_AT, nowMs)
                connection.commit()
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
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
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

    /**
     * Import legacy project-local const-ref db (`build/jugg/database/const_ref.db`) into shared schema.
     */
    @Synchronized
    fun importLegacyProjectDatabase(legacyDbFile: File): ImportResult {
        if (!legacyDbFile.exists() || legacyDbFile.absolutePath == dbFile.absolutePath) {
            return ImportResult(skipped = true, importedFiles = 0, importedDefinitions = 0, importedReferences = 0)
        }
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${legacyDbFile.absolutePath}").use { legacyConnection ->
                if (!tableExists(legacyConnection, "file_cache")) {
                    return@use ImportResult(
                        skipped = true,
                        importedFiles = 0,
                        importedDefinitions = 0,
                        importedReferences = 0,
                    )
                }
                val fileRows = mutableListOf<LegacyFileRow>()
                legacyConnection.createStatement().use { statement ->
                    statement.executeQuery("SELECT file_path, last_modified, checksum FROM file_cache").use { resultSet ->
                        while (resultSet.next()) {
                            fileRows += LegacyFileRow(
                                filePath = resultSet.getString("file_path"),
                                lastModified = resultSet.getLong("last_modified"),
                                checksum = resultSet.getLong("checksum"),
                            )
                        }
                    }
                }
                if (fileRows.isEmpty()) {
                    return@use ImportResult(skipped = true, importedFiles = 0, importedDefinitions = 0, importedReferences = 0)
                }

                var importedFiles = 0
                var importedDefinitions = 0
                var importedReferences = 0
                legacyConnection.prepareStatement(
                    """
                    SELECT package_name, fq_class_name, const_name, const_type, const_value
                    FROM const_definitions
                    WHERE file_path = ?
                    """.trimIndent()
                ).use { definitionStatement ->
                    legacyConnection.prepareStatement(
                        """
                        SELECT def_fq_class_name, const_name
                        FROM const_references
                        WHERE ref_file_path = ?
                        """.trimIndent()
                    ).use { referenceStatement ->
                        fileRows.forEach { row ->
                            val definitions = mutableListOf<ConstDefinition>()
                            definitionStatement.setString(1, row.filePath)
                            definitionStatement.executeQuery().use { definitionResultSet ->
                                while (definitionResultSet.next()) {
                                    definitions += ConstDefinition(
                                        filePath = row.filePath,
                                        packageName = definitionResultSet.getString("package_name"),
                                        fqClassName = definitionResultSet.getString("fq_class_name"),
                                        constName = definitionResultSet.getString("const_name"),
                                        constType = definitionResultSet.getString("const_type"),
                                        constValue = definitionResultSet.getString("const_value"),
                                    )
                                }
                            }

                            val references = mutableListOf<ConstReference>()
                            referenceStatement.setString(1, row.filePath)
                            referenceStatement.executeQuery().use { referenceResultSet ->
                                while (referenceResultSet.next()) {
                                    references += ConstReference(
                                        refFilePath = row.filePath,
                                        defFqClassName = referenceResultSet.getString("def_fq_class_name"),
                                        constName = referenceResultSet.getString("const_name"),
                                    )
                                }
                            }

                            upsertFileAnalysis(
                                filePath = row.filePath,
                                lastModified = row.lastModified,
                                checksum = row.checksum,
                                definitions = definitions,
                                references = references,
                            )
                            importedFiles++
                            importedDefinitions += definitions.size
                            importedReferences += references.size
                        }
                    }
                }
                ImportResult(
                    skipped = false,
                    importedFiles = importedFiles,
                    importedDefinitions = importedDefinitions,
                    importedReferences = importedReferences,
                )
            }
        }.onFailure { throwable ->
            logger.warn(
                "import legacy const-ref db failed, from=${legacyDbFile.absolutePath}, to=${dbFile.absolutePath}",
                throwable,
            )
        }.getOrElse {
            ImportResult(skipped = true, importedFiles = 0, importedDefinitions = 0, importedReferences = 0)
        }
    }

    private fun recreateDatabase() {
        runCatching {
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }.onFailure {
            logger.warn("delete old const-ref db file failed, dbFile=${dbFile.absolutePath}", it)
        }
        withConnection { connection ->
            ensureSchema(connection)
        }
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS file_checksum_mtime_map (
                    worktree_key TEXT NOT NULL,
                    repo_key TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    last_modified INTEGER NOT NULL,
                    checksum INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (worktree_key, relative_path)
                );
                CREATE INDEX IF NOT EXISTS idx_mtime_map_updated ON file_checksum_mtime_map(updated_at);
                CREATE INDEX IF NOT EXISTS idx_mtime_map_checksum ON file_checksum_mtime_map(repo_key, relative_path, checksum);

                CREATE TABLE IF NOT EXISTS file_analysis_head (
                    repo_key TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    checksum INTEGER NOT NULL,
                    analyzed_at INTEGER NOT NULL,
                    last_access_at INTEGER NOT NULL,
                    PRIMARY KEY (repo_key, relative_path, checksum)
                );
                CREATE INDEX IF NOT EXISTS idx_analysis_head_access ON file_analysis_head(last_access_at);
                CREATE INDEX IF NOT EXISTS idx_analysis_head_repo_file ON file_analysis_head(repo_key, relative_path);

                CREATE TABLE IF NOT EXISTS const_definitions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_key TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    checksum INTEGER NOT NULL,
                    package_name TEXT NOT NULL,
                    fq_class_name TEXT NOT NULL,
                    const_name TEXT NOT NULL,
                    const_type TEXT NOT NULL,
                    const_value TEXT,
                    FOREIGN KEY (repo_key, relative_path, checksum)
                        REFERENCES file_analysis_head(repo_key, relative_path, checksum)
                        ON DELETE CASCADE
                );
                DROP INDEX IF EXISTS idx_const_def_unique;
                CREATE UNIQUE INDEX IF NOT EXISTS idx_const_def_unique
                    ON const_definitions(repo_key, relative_path, checksum, fq_class_name, const_name);
                CREATE INDEX IF NOT EXISTS idx_const_def_file_version
                    ON const_definitions(repo_key, relative_path, checksum);
                CREATE INDEX IF NOT EXISTS idx_const_def_repo_package_const
                    ON const_definitions(repo_key, package_name, const_name);

                CREATE TABLE IF NOT EXISTS const_references (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_key TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    checksum INTEGER NOT NULL,
                    def_fq_class_name TEXT NOT NULL,
                    const_name TEXT NOT NULL,
                    FOREIGN KEY (repo_key, relative_path, checksum)
                        REFERENCES file_analysis_head(repo_key, relative_path, checksum)
                        ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_ref_repo_def_class
                    ON const_references(repo_key, def_fq_class_name, const_name);
                CREATE INDEX IF NOT EXISTS idx_ref_file_version
                    ON const_references(repo_key, relative_path, checksum);

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
        connection.prepareStatement(
            """
            SELECT checksum
            FROM file_analysis_head
            WHERE repo_key = ?
              AND relative_path = ?
            ORDER BY analyzed_at DESC, last_access_at DESC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, repoKey)
            statement.setString(2, relativePath)
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
            val sql = "${buildLatestDefinitionsSql(scopeRepoKeys)} WHERE $whereClause"
            connection.prepareStatement(sql).use { statement ->
                var paramIndex = 1
                scopeRepoKeys.forEach { repoKey ->
                    statement.setString(paramIndex++, repoKey)
                }
                bindExtraParams(statement, paramIndex)
                statement.executeQuery().use { resultSet ->
                    buildDefinitions(resultSet, repoRoots, excludedIdentityKeys = emptySet())
                }
            }
        }
    }

    private fun buildLatestDefinitionsSql(scopeRepoKeys: Set<String>): String {
        val repoPlaceholders = scopeRepoKeys.joinToString(",") { "?" }
        return """
            WITH latest AS (
                SELECT repo_key,
                       relative_path,
                       checksum
                FROM (
                    SELECT repo_key,
                           relative_path,
                           checksum,
                           ROW_NUMBER() OVER (
                               PARTITION BY repo_key, relative_path
                               ORDER BY analyzed_at DESC, last_access_at DESC
                           ) AS rank_num
                    FROM file_analysis_head
                    WHERE repo_key IN ($repoPlaceholders)
                ) ranked
                WHERE rank_num = 1
            )
            SELECT d.repo_key, d.relative_path, d.package_name, d.fq_class_name, d.const_name, d.const_type, d.const_value
            FROM const_definitions d
            INNER JOIN latest l
                ON l.repo_key = d.repo_key
               AND l.relative_path = d.relative_path
               AND l.checksum = d.checksum
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
                connection.prepareStatement(
                    """
                    SELECT package_name, fq_class_name, const_name, const_type, const_value
                    FROM const_definitions
                    WHERE repo_key = ?
                      AND relative_path = ?
                      AND checksum = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, identity.repoKey)
                    statement.setString(2, identity.relativePath)
                    statement.setLong(3, checksum)
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

    private fun queryEffectedFilesByDefinitionKeys(
        definitionKeys: Set<Pair<String, String>>,
        scopeRepoKeys: Set<String>,
    ): List<EffectedConstRef> {
        if (definitionKeys.isEmpty()) {
            return emptyList()
        }
        val repoRoots = repoRootByKey.toMap()
        return withConnection { connection ->
            val effectedSet = linkedSetOf<EffectedConstRef>()
            definitionKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
                val whereClause = chunk.joinToString(" OR ") { "(def_fq_class_name = ? AND const_name = ?)" }
                val sql = buildString {
                    append(
                        """
                        SELECT repo_key, relative_path, def_fq_class_name, const_name
                        FROM const_references
                        WHERE ($whereClause)
                        """.trimIndent()
                    )
                    if (scopeRepoKeys.isNotEmpty()) {
                        append(" AND repo_key IN (${scopeRepoKeys.joinToString(",") { "?" }})")
                    }
                }
                connection.prepareStatement(sql).use { statement ->
                    var paramIndex = 1
                    chunk.forEach { (fqClassName, constName) ->
                        statement.setString(paramIndex++, fqClassName)
                        statement.setString(paramIndex++, constName)
                    }
                    if (scopeRepoKeys.isNotEmpty()) {
                        scopeRepoKeys.forEach { repoKey ->
                            statement.setString(paramIndex++, repoKey)
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

    private fun upsertMtimeMap(
        connection: Connection,
        worktreeKey: String,
        repoKey: String,
        relativePath: String,
        lastModified: Long,
        checksum: Long,
        updatedAt: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO file_checksum_mtime_map(worktree_key, repo_key, relative_path, last_modified, checksum, updated_at)
            VALUES(?, ?, ?, ?, ?, ?)
            ON CONFLICT(worktree_key, relative_path)
            DO UPDATE SET repo_key = excluded.repo_key,
                          last_modified = excluded.last_modified,
                          checksum = excluded.checksum,
                          updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, worktreeKey)
            statement.setString(2, repoKey)
            statement.setString(3, relativePath)
            statement.setLong(4, lastModified)
            statement.setLong(5, checksum)
            statement.setLong(6, updatedAt)
            statement.executeUpdate()
        }
    }

    private fun runCheckpointAndVacuumIfNeeded(nowMs: Long, force: Boolean): MaintenanceResult {
        return withConnection { connection ->
            val lastVacuumAt = readMetaLong(connection, META_LAST_VACUUM_AT) ?: 0L
            if (!force && nowMs - lastVacuumAt < VACUUM_INTERVAL_MS) {
                return@withConnection MaintenanceResult(
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
        DriverManager.getConnection(url).use { connection ->
            applyConnectionPragmas(connection)
            return block(connection)
        }
    }

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

    data class ImportResult(
        val skipped: Boolean,
        val importedFiles: Int,
        val importedDefinitions: Int,
        val importedReferences: Int,
    )

    private data class MaintenanceResult(
        val checkpointExecuted: Boolean,
        val vacuumExecuted: Boolean,
    )

    private data class LegacyFileRow(
        val filePath: String,
        val lastModified: Long,
        val checksum: Long,
    )

    private data class ReusableFileIdentity(
        val worktreeKey: String,
        val repoKey: String,
        val relativePath: String,
        val lastModified: Long,
    )

    companion object {
        private const val DB_SCHEMA_VERSION = 3
        private const val MAX_MTIME_QUERY_ROWS_PER_BATCH = 250
        private const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val MTIME_MAP_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val ANALYSIS_TTL_MS = 90L * 24L * 60L * 60L * 1000L
        private const val MAX_MTIME_ENTRIES_PER_FILE = 20
        private const val MAX_ANALYSIS_ENTRIES_PER_FILE = 5
        private const val VACUUM_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val VACUUM_TRIGGER_BYTES = 256L * 1024L * 1024L
        private const val META_LAST_CLEANUP_AT = "last_cleanup_at"
        private const val META_LAST_VACUUM_AT = "last_vacuum_at"
    }
}
