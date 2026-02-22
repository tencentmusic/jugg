package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.SqLiteDriverLoader
import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.CRC32

class RepoSharedFingerprintStore(
    private val logger: Logger,
    private val dbFile: File = File(System.getProperty("user.home"), ".jugg/const_ref/repo_fingerprint.db"),
) {
    private val sampleSize = 4096
    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    init {
        init()
    }

    @Synchronized
    fun findChecksum(file: File): Long? {
        val repoFileKey = resolveRepoFileKey(file) ?: return null
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
        val repoFileKey = resolveRepoFileKey(file) ?: return
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
                    """.trimIndent()
                )
            }
        }
    }

    private fun resolveRepoFileKey(file: File): RepoFileKey? {
        if (!file.exists()) {
            return null
        }
        val absoluteFile = file.absoluteFile
        var currentDir = absoluteFile.parentFile
        while (currentDir != null) {
            val gitRef = File(currentDir, ".git")
            if (gitRef.exists()) {
                val gitDir = resolveGitDir(currentDir, gitRef) ?: return null
                val repoKey = resolveRepoKey(gitDir)
                val relativePath = absoluteFile.relativeTo(currentDir).invariantSeparatorsPath
                return RepoFileKey(repoKey = repoKey, relativePath = relativePath)
            }
            currentDir = currentDir.parentFile
        }
        return null
    }

    private fun resolveGitDir(worktreeRoot: File, gitRef: File): File? {
        return if (gitRef.isDirectory) {
            gitRef.canonicalFile
        } else if (gitRef.isFile) {
            val gitPath = gitRef.readText()
                .lineSequence()
                .firstOrNull()
                ?.substringAfter("gitdir:", "")
                ?.trim()
                .orEmpty()
            if (gitPath.isEmpty()) {
                null
            } else {
                val resolved = if (File(gitPath).isAbsolute) File(gitPath) else File(worktreeRoot, gitPath)
                resolved.canonicalFile
            }
        } else {
            null
        }
    }

    private fun resolveRepoKey(gitDir: File): String {
        val commonDirFile = File(gitDir, "commondir")
        if (!commonDirFile.exists()) {
            return gitDir.canonicalPath
        }
        val commonDir = commonDirFile.readText().trim()
        if (commonDir.isEmpty()) {
            return gitDir.canonicalPath
        }
        val commonGitDir = if (File(commonDir).isAbsolute) {
            File(commonDir)
        } else {
            File(gitDir, commonDir)
        }
        return commonGitDir.canonicalPath
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
            statement.execute("PRAGMA cache_size=-16000")
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

    private data class RepoFileKey(
        val repoKey: String,
        val relativePath: String,
    )

    private data class ContentSignature(
        val fileSize: Long,
        val headChecksum: Long,
        val tailChecksum: Long,
    )
}
