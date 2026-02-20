package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.ChangedFile
import org.sqlite.SQLiteException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.*


/**
 * Maintains source-file -> class-name index and self-heals SQLite state on corruption/aging.
 */
class SourceFileManager(
    projectDir: File,
    dbDir: File,
    private val logger: Logger,
) {

    private val dbFile = File(dbDir, "source_files.db")
    private val database = SourceFileDatabaseSqLiteHelper(projectDir, dbFile, logger.getInstance("SourceFileDatabaseSqLiteHelper"))

    private var sourceDirs = emptyList<File>()

    private var isCanRecreateOnError = true

    @Synchronized
    fun init(sourceDirs: List<File>) {
        this.sourceDirs = sourceDirs

        val startTime = System.currentTimeMillis()
        try {
            if (isNeedRecreate()) {
                database.recreateDatabase()
            }

            database.init()
            database.updateSourceDirs(sourceDirs)
            isCanRecreateOnError = true
        } catch (e: Exception) {
            logger.warn("init error", e)
            if (isCanRecreateOnError && (e is SQLiteException)) {
                isCanRecreateOnError = false
                logger.debug("get SQLiteException on init, recreate database")
                database.recreateDatabase()
                init(sourceDirs)
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
        val attr: BasicFileAttributes = Files.readAttributes(dbFile.toPath(), BasicFileAttributes::class.java)
        val creationTime = attr.creationTime()
        val creationTimeString = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(creationTime.toMillis()))
        val daysSinceCreated = (System.currentTimeMillis() - creationTime.toMillis()) / (1000 * 60 * 60 * 24)
        logger.debug("dbFile creationTime: $creationTimeString, daysSinceCreated: $daysSinceCreated")
        if (daysSinceCreated > 14) {
            logger.debug("dbFile is too old, recreate database")
            return true
        }
        return false
    }

    @Synchronized
    fun updateFiles(addFiles: List<ChangedFile>, deleteFiles: List<File>) {
        val startTime = System.currentTimeMillis()
        try {
            database.updateFiles(addFiles, deleteFiles)
        } catch (e: Exception) {
            logger.warn("updateFiles error", e)
            if (isCanRecreateOnError && (e is SQLiteException)) {
                logger.debug("get SQLiteException on updateFiles, recreate database")
                database.recreateDatabase()
                init(sourceDirs)
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
}
