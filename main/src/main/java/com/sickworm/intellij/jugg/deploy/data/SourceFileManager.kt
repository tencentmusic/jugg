package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.ChangedFile
import org.sqlite.SQLiteException
import java.io.File

class SourceFileManager(
    projectDir: File,
    dbDir: File,
    private val logger: Logger,
) {

    private val database = SourceFileDatabaseSqLiteHelper(projectDir, File(dbDir, "source_files.db"), logger.getInstance("SourceFileDatabaseSqLiteHelper"))

    private var sourceDirs = emptyList<File>()

    private var isCanRecreateOnError = true

    @Synchronized
    fun init(sourceDirs: List<File>) {
        this.sourceDirs = sourceDirs

        val startTime = System.currentTimeMillis()
        try {
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