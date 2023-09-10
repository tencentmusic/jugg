package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File
import java.sql.DriverManager

class SourceFileManager(private val logger: Logger, dbDir: File) {

    private val database = SourceFileDatabaseSqLiteHelper(File(dbDir, "source_files.db"), logger.getInstance("SourceFileDatabaseSqLiteHelper"))

    @Synchronized
    fun init(sourceDirs: List<File>) {
        val startTime = System.currentTimeMillis()
        try {
            database.init()
            database.updateSourceDirs(sourceDirs)
        } catch (e: Exception) {
            logger.warn("init error", e)
        }
        val costTime = System.currentTimeMillis() - startTime
        if (costTime >= 100) {
            logger.debug("source file db init cost ${costTime}ms")
        }
    }

    @Synchronized
    fun updateFiles(addFiles: List<File>, deleteFiles: List<File>) {
        val startTime = System.currentTimeMillis()
        try {
            database.updateFiles(addFiles, deleteFiles)
        } catch (e: Exception) {
            logger.warn("updateFiles error", e)
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