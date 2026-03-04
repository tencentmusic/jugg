package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.SqLiteDriverLoader
import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.CRC32

/**
 * Shared fingerprint index used to restore file checksum without reading full file content.
 *
 * The storage key is based on `repoKey + relativePath`, so multiple projects/worktrees can reuse it.
 */
class RepoSharedFingerprintStore(
    private val logger: Logger,
    private val dbFile: File,
) {
    private val sampleSize = 4096
    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    init {
        init()
    }

    @Synchronized
    fun findChecksum(file: File): Long? {
        val repoFileKey = ConstRefRepoPathResolver.resolve(file) ?: return null
        val signature = buildContentSignature(file) ?: return null
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT checksum
                FROM repo_fingerprint
                WHERE repo_key = ?
                  AND relative_path = ?
                  AND file_size = ?
                  AND head_checksum = ?
                  AND tail_checksum = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoFileKey.repoKey)
                statement.setString(2, repoFileKey.relativePath)
                statement.setLong(3, signature.fileSize)
                statement.setLong(4, signature.headChecksum)
                statement.setLong(5, signature.tailChecksum)
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
    fun saveChecksum(file: File, checksum: Long) {
        val repoFileKey = ConstRefRepoPathResolver.resolve(file) ?: return
        val signature = buildContentSignature(file) ?: return
        withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO repo_fingerprint(
                    repo_key, relative_path, file_size, head_checksum, tail_checksum, checksum, updated_at
                )
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(repo_key, relative_path, file_size, head_checksum, tail_checksum)
                DO UPDATE SET checksum = excluded.checksum,
                              updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, repoFileKey.repoKey)
                statement.setString(2, repoFileKey.relativePath)
                statement.setLong(3, signature.fileSize)
                statement.setLong(4, signature.headChecksum)
                statement.setLong(5, signature.tailChecksum)
                statement.setLong(6, checksum)
                statement.setLong(7, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }

    /**
     * Clean old fingerprint entries and compact db with throttle control.
     */
    @Synchronized
    fun cleanupIfNeeded(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): CleanupResult {
        val cleanupStats = withConnection { connection ->
            val lastCleanupAt = readMetaLong(connection, META_LAST_CLEANUP_AT) ?: 0L
            if (!force && nowMs - lastCleanupAt < CLEANUP_INTERVAL_MS) {
                return@withConnection CleanupResult(
                    executed = false,
                    removedExpiredRows = 0,
                    removedOverflowRows = 0,
                    checkpointExecuted = false,
                    vacuumExecuted = false,
                )
            }

            connection.autoCommit = false
            try {
                val removedExpiredRows = executeDelete(
                    connection = connection,
                    sql = "DELETE FROM repo_fingerprint WHERE updated_at < ?",
                    params = arrayOf(nowMs - FINGERPRINT_TTL_MS),
                )
                val removedOverflowRows = executeDelete(
                    connection = connection,
                    sql = """
                        DELETE FROM repo_fingerprint
                        WHERE rowid IN (
                            SELECT rowid FROM (
                                SELECT rowid,
                                       ROW_NUMBER() OVER (
                                           PARTITION BY repo_key, relative_path
                                           ORDER BY updated_at DESC, file_size DESC
                                       ) AS rank_num
                                FROM repo_fingerprint
                            ) ranked
                            WHERE ranked.rank_num > ?
                        )
                    """.trimIndent(),
                    params = arrayOf(MAX_FINGERPRINT_ENTRIES_PER_FILE),
                )
                writeMetaLong(connection, META_LAST_CLEANUP_AT, nowMs)
                connection.commit()
                CleanupResult(
                    executed = true,
                    removedExpiredRows = removedExpiredRows,
                    removedOverflowRows = removedOverflowRows,
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

    @Synchronized
    private fun init() {
        SqLiteDriverLoader.load(logger)
        dbFile.parentFile?.mkdirs()
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS repo_fingerprint (
                        repo_key TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        head_checksum INTEGER NOT NULL,
                        tail_checksum INTEGER NOT NULL,
                        checksum INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (repo_key, relative_path, file_size, head_checksum, tail_checksum)
                    );
                    CREATE INDEX IF NOT EXISTS idx_repo_fingerprint_path ON repo_fingerprint(repo_key, relative_path);
                    CREATE INDEX IF NOT EXISTS idx_repo_fingerprint_updated ON repo_fingerprint(updated_at);

                    CREATE TABLE IF NOT EXISTS maintenance_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    );
                    """.trimIndent()
                )
            }
        }
    }

    private fun buildContentSignature(file: File): ContentSignature? {
        if (!file.exists() || !file.isFile) {
            return null
        }
        val fileSize = file.length()
        val headSize = minOf(sampleSize.toLong(), fileSize).toInt()
        val tailSize = headSize
        val headBytes = ByteArray(headSize)
        val tailBytes = ByteArray(tailSize)
        val middleSize = if (fileSize > sampleSize.toLong() * 2) {
            sampleSize
        } else {
            0
        }
        val middleBytes = ByteArray(middleSize)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (headSize > 0) {
                    raf.readFully(headBytes)
                    val tailOffset = (fileSize - tailSize).coerceAtLeast(0L)
                    raf.seek(tailOffset)
                    raf.readFully(tailBytes)
                    if (middleSize > 0) {
                        val middleOffset = (fileSize - middleSize).coerceAtLeast(0L) / 2
                        raf.seek(middleOffset)
                        raf.readFully(middleBytes)
                    }
                }
            }
            ContentSignature(
                fileSize = fileSize,
                headChecksum = crc32(headBytes, middleBytes),
                tailChecksum = crc32(tailBytes),
            )
        } catch (t: Throwable) {
            logger.debug("buildContentSignature failed for file=$file", t)
            null
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

    private fun crc32(vararg bytes: ByteArray): Long {
        val crc32 = CRC32()
        bytes.forEach { chunk ->
            if (chunk.isNotEmpty()) {
                crc32.update(chunk)
            }
        }
        return crc32.value
    }

    private fun applyConnectionPragmas(connection: Connection) {
        connection.createStatement().use { statement ->
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

    data class CleanupResult(
        val executed: Boolean,
        val removedExpiredRows: Int,
        val removedOverflowRows: Int,
        val checkpointExecuted: Boolean,
        val vacuumExecuted: Boolean,
    )

    private data class MaintenanceResult(
        val checkpointExecuted: Boolean,
        val vacuumExecuted: Boolean,
    )

    private data class ContentSignature(
        val fileSize: Long,
        val headChecksum: Long,
        val tailChecksum: Long,
    )

    companion object {
        private const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val FINGERPRINT_TTL_MS = 60L * 24L * 60L * 60L * 1000L
        private const val MAX_FINGERPRINT_ENTRIES_PER_FILE = 10
        private const val VACUUM_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val VACUUM_TRIGGER_BYTES = 256L * 1024L * 1024L
        private const val META_LAST_CLEANUP_AT = "last_cleanup_at"
        private const val META_LAST_VACUUM_AT = "last_vacuum_at"
    }
}
