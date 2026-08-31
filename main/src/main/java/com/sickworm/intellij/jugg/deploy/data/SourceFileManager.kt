package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.change.ChangedFile
import org.sqlite.SQLiteException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID


/**
 * Maintains source-file -> class-name index and self-heals SQLite state on corruption/aging.
 */
class SourceFileManager(
    projectDir: File,
    dbDir: File,
    private val logger: Logger,
) {

    private val dbFile = File(dbDir, "source_files.db")
    private val rebuildStampFile = File(dbDir, "source_files.rebuild_at")
    private val database = SourceFileDatabaseSqLiteHelper(projectDir, dbFile, logger.getInstance("SourceFileDatabaseSqLiteHelper"))

    private var sourceDirs = emptyList<File>()

    private var isCanRecreateOnError = true

    @Synchronized
    fun init(sourceDirs: List<File>) {
        this.sourceDirs = sourceDirs
        initDatabase(sourceDirs, forceRecreate = false)
    }

    private fun initDatabase(sourceDirs: List<File>, forceRecreate: Boolean) {
        val startTime = System.currentTimeMillis()
        try {
            val databaseExists = dbFile.exists()
            val shouldRecreate = forceRecreate || isNeedRecreate()
            val isRecreated = if (shouldRecreate) {
                database.recreateDatabase()
                true
            } else {
                database.init()
            }

            database.updateSourceDirs(sourceDirs)
            if (!databaseExists || isRecreated) {
                try {
                    writeLastSuccessfulRebuildAt(System.currentTimeMillis())
                } catch (e: Exception) {
                    logger.warn("Write source file db rebuild stamp failed", e)
                }
            }
            isCanRecreateOnError = true
        } catch (e: Exception) {
            logger.warn("init error", e)
            if (isCanRecreateOnError && (e is SQLiteException)) {
                isCanRecreateOnError = false
                logger.debug("get SQLiteException on init, recreate database")
                initDatabase(sourceDirs, forceRecreate = true)
            } else {
                isCanRecreateOnError = false
            }
        }
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("source file db init cost ${costTime}ms")
    }

    private fun isNeedRecreate(): Boolean {
        if (!dbFile.exists()) {
            return false
        }
        val lastSuccessfulRebuildAt = readLastSuccessfulRebuildAt() ?: return true
        val now = System.currentTimeMillis()
        if (lastSuccessfulRebuildAt > now + FUTURE_TIMESTAMP_TOLERANCE_MS) {
            logger.debug("source file db rebuild stamp is in the future, recreate database")
            return true
        }
        val ageMillis = (now - lastSuccessfulRebuildAt).coerceAtLeast(0)
        logger.debug("source file db daysSinceRebuilt: ${ageMillis / DAY_MILLIS}")
        if (ageMillis > MAX_DATABASE_AGE_MILLIS) {
            logger.debug("source file db is too old, recreate database")
            return true
        }
        return false
    }

    private fun readLastSuccessfulRebuildAt(): Long? {
        if (!rebuildStampFile.isFile) {
            logger.debug("source file db rebuild stamp is missing, recreate database")
            return null
        }
        return try {
            rebuildStampFile.readText(Charsets.UTF_8).trim().toLongOrNull()
                ?.takeIf { it > 0 }
                ?: run {
                    logger.debug("source file db rebuild stamp is invalid, recreate database")
                    null
                }
        } catch (e: Exception) {
            logger.warn("Read source file db rebuild stamp failed", e)
            null
        }
    }

    private fun writeLastSuccessfulRebuildAt(timestamp: Long) {
        rebuildStampFile.parentFile?.mkdirs()
        val tempFile = File(rebuildStampFile.parentFile, "${rebuildStampFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(timestamp.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    rebuildStampFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), rebuildStampFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Synchronized
    fun updateFiles(addFiles: List<ChangedFile>, deleteFiles: List<File>) {
        val startTime = System.currentTimeMillis()
        try {
            database.updateFiles(addFiles, deleteFiles)
        } catch (e: Exception) {
            logger.warn("updateFiles error", e)
            if (isCanRecreateOnError && (e is SQLiteException)) {
                isCanRecreateOnError = false
                logger.debug("get SQLiteException on updateFiles, recreate database")
                initDatabase(sourceDirs, forceRecreate = true)
            }
        }
        val costTime = System.currentTimeMillis() - startTime
        if (costTime >= 100) {
            logger.debug("updateFiles cost ${costTime}ms")
        }
    }

    @Synchronized
    fun getFiles(classNames: List<String>): List<File> {
        return try {
            val startTime = System.currentTimeMillis()
            val files = database.getFiles(classNames)
            val costTime = System.currentTimeMillis() - startTime
            if (costTime >= 100) {
                logger.debug("getFiles cost ${costTime}ms")
            }
            files
        } catch (e: Exception) {
            logger.warn("getFiles error", e)
            emptyList()
        }
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_DATABASE_AGE_MILLIS = 14 * DAY_MILLIS
        private const val FUTURE_TIMESTAMP_TOLERANCE_MS = 5L * 60 * 1000
    }
}
