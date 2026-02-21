package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.SqLiteDriverLoader
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class ConstRefCacheDatabase(
    private val dbFile: File,
    private val logger: Logger,
) {
    private val maxDefinitionKeysPerQuery = 400
    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    init {
        init()
    }

    @Synchronized
    fun init() {
        SqLiteDriverLoader.load(logger)
        dbFile.parentFile?.mkdirs()
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS file_cache (
                        file_path TEXT PRIMARY KEY,
                        last_modified INTEGER NOT NULL,
                        checksum INTEGER NOT NULL,
                        analyzed_at INTEGER NOT NULL
                    );

                    CREATE TABLE IF NOT EXISTS const_definitions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_path TEXT NOT NULL,
                        package_name TEXT NOT NULL,
                        fq_class_name TEXT NOT NULL,
                        const_name TEXT NOT NULL,
                        const_type TEXT NOT NULL,
                        const_value TEXT,
                        FOREIGN KEY (file_path) REFERENCES file_cache(file_path) ON DELETE CASCADE
                    );
                    DROP INDEX IF EXISTS idx_const_def_unique;
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_const_def_unique ON const_definitions(file_path, fq_class_name, const_name);
                    CREATE INDEX IF NOT EXISTS idx_const_def_file ON const_definitions(file_path);
                    CREATE INDEX IF NOT EXISTS idx_const_def_package_const ON const_definitions(package_name, const_name);

                    CREATE TABLE IF NOT EXISTS const_references (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ref_file_path TEXT NOT NULL,
                        def_fq_class_name TEXT NOT NULL,
                        const_name TEXT NOT NULL,
                        FOREIGN KEY (ref_file_path) REFERENCES file_cache(file_path) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_ref_def_class ON const_references(def_fq_class_name, const_name);
                    CREATE INDEX IF NOT EXISTS idx_ref_file ON const_references(ref_file_path);
                    """.trimIndent()
                )
            }
        }
    }

    @Synchronized
    fun getFileCache(filePath: String): FileCacheEntry? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT file_path, last_modified, checksum, analyzed_at
                FROM file_cache
                WHERE file_path = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, filePath)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return@withConnection null
                    }
                    return@withConnection FileCacheEntry(
                        filePath = resultSet.getString("file_path"),
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
        withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO file_cache(file_path, last_modified, checksum, analyzed_at)
                    VALUES(?, ?, ?, ?)
                    ON CONFLICT(file_path)
                    DO UPDATE SET last_modified=excluded.last_modified,
                                  checksum=excluded.checksum,
                                  analyzed_at=excluded.analyzed_at
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, filePath)
                    statement.setLong(2, lastModified)
                    statement.setLong(3, checksum)
                    statement.setLong(4, System.currentTimeMillis())
                    statement.executeUpdate()
                }

                connection.prepareStatement("DELETE FROM const_definitions WHERE file_path = ?").use { statement ->
                    statement.setString(1, filePath)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM const_references WHERE ref_file_path = ?").use { statement ->
                    statement.setString(1, filePath)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO const_definitions(file_path, package_name, fq_class_name, const_name, const_type, const_value)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    definitions.forEach { definition ->
                        statement.setString(1, filePath)
                        statement.setString(2, definition.packageName)
                        statement.setString(3, definition.fqClassName)
                        statement.setString(4, definition.constName)
                        statement.setString(5, definition.constType)
                        statement.setString(6, definition.constValue)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO const_references(ref_file_path, def_fq_class_name, const_name)
                    VALUES(?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    references.forEach { reference ->
                        statement.setString(1, filePath)
                        statement.setString(2, reference.defFqClassName)
                        statement.setString(3, reference.constName)
                        statement.addBatch()
                    }
                    statement.executeBatch()
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
    fun updateFileLastModified(filePath: String, lastModified: Long) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE file_cache
                SET last_modified = ?, analyzed_at = ?
                WHERE file_path = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, lastModified)
                statement.setLong(2, System.currentTimeMillis())
                statement.setString(3, filePath)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun removeFile(filePath: String) {
        withConnection { connection ->
            connection.prepareStatement("DELETE FROM file_cache WHERE file_path = ?").use { statement ->
                statement.setString(1, filePath)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun removeFilesByPrefix(prefixPath: String) {
        withConnection { connection ->
            connection.prepareStatement("DELETE FROM file_cache WHERE file_path LIKE ?").use { statement ->
                statement.setString(1, "$prefixPath%")
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getAllDefinitions(excludeFilePaths: Set<String> = emptySet()): List<ConstDefinition> {
        return withConnection { connection ->
            val sql = buildString {
                append("SELECT file_path, package_name, fq_class_name, const_name, const_type, const_value FROM const_definitions")
                if (excludeFilePaths.isNotEmpty()) {
                    append(" WHERE file_path NOT IN (${excludeFilePaths.joinToString(",") { "?" }})")
                }
            }
            connection.prepareStatement(sql).use { statement ->
                excludeFilePaths.forEachIndexed { index, filePath ->
                    statement.setString(index + 1, filePath)
                }
                statement.executeQuery().use { resultSet ->
                    buildDefinitions(resultSet)
                }
            }
        }
    }

    @Synchronized
    fun getDefinitionsByFiles(filePaths: Collection<String>): List<ConstDefinition> {
        val normalizedPaths = filePaths.distinct()
        if (normalizedPaths.isEmpty()) {
            return emptyList()
        }
        return withConnection { connection ->
            val placeholders = normalizedPaths.joinToString(",") { "?" }
            val sql = """
                SELECT file_path, package_name, fq_class_name, const_name, const_type, const_value
                FROM const_definitions
                WHERE file_path IN ($placeholders)
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                normalizedPaths.forEachIndexed { index, filePath ->
                    statement.setString(index + 1, filePath)
                }
                statement.executeQuery().use { resultSet ->
                    buildDefinitions(resultSet)
                }
            }
        }
    }

    @Synchronized
    fun getEffectedFiles(changedFilePaths: Collection<String>): List<EffectedConstRef> {
        val changedDefinitions = getDefinitionsByFiles(changedFilePaths)
        if (changedDefinitions.isEmpty()) {
            return emptyList()
        }
        return getEffectedFilesByDefinitions(changedDefinitions)
    }

    @Synchronized
    fun getEffectedFilesByDefinitions(definitions: Collection<ConstDefinition>): List<EffectedConstRef> {
        val uniqueKeys = definitions.map { it.fqClassName to it.constName }.toSet()
        return getEffectedFilesByDefinitionKeys(uniqueKeys)
    }

    @Synchronized
    fun getEffectedFilesByDefinitionKeys(definitionKeys: Collection<Pair<String, String>>): List<EffectedConstRef> {
        val uniqueKeys = definitionKeys.toSet()
        if (uniqueKeys.isEmpty()) {
            return emptyList()
        }
        return withConnection { connection ->
            val effectedSet = linkedSetOf<EffectedConstRef>()
            uniqueKeys.chunked(maxDefinitionKeysPerQuery).forEach { chunk ->
                val whereClause = chunk.joinToString(" OR ") { "(def_fq_class_name = ? AND const_name = ?)" }
                val sql = """
                    SELECT ref_file_path, def_fq_class_name, const_name
                    FROM const_references
                    WHERE $whereClause
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    var paramIndex = 1
                    chunk.forEach { (fqClassName, constName) ->
                        statement.setString(paramIndex++, fqClassName)
                        statement.setString(paramIndex++, constName)
                    }
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            effectedSet += EffectedConstRef(
                                refFilePath = resultSet.getString("ref_file_path"),
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

    private fun applyConnectionPragmas(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA cache_size=-64000")
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

    private fun buildDefinitions(resultSet: java.sql.ResultSet): List<ConstDefinition> {
        val definitions = mutableListOf<ConstDefinition>()
        while (resultSet.next()) {
            definitions += ConstDefinition(
                filePath = resultSet.getString("file_path"),
                packageName = resultSet.getString("package_name"),
                fqClassName = resultSet.getString("fq_class_name"),
                constName = resultSet.getString("const_name"),
                constType = resultSet.getString("const_type"),
                constValue = resultSet.getString("const_value"),
            )
        }
        return definitions
    }

    data class FileCacheEntry(
        val filePath: String,
        val lastModified: Long,
        val checksum: Long,
        val analyzedAt: Long,
    )
}
