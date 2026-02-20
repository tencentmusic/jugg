package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * SourceFileDatabaseSqLiteHelper provides helper utilities for source sq lite.
 */
class SourceFileDatabaseSqLiteHelper(private val projectDir: File, private val dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    companion object {
        private const val VERSION = 3
    }

    @Synchronized
    fun init(isRecreate: Boolean = false) {
        SqLiteDriverLoader.load(logger)
        dbFile.parentFile?.mkdirs()

        // Create a new database connection
        DriverManager.getConnection(url).use { connection ->
            val readVersionSQL = "PRAGMA schema_version;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(readVersionSQL)
                if (resultSet.next()) {
                    val version = resultSet.getInt(1)
                    logger.debug("Current database version: ${if (version == 0) "not set" else "$version"}")
                    if (version > 0 && version != VERSION) {
                        logger.debug("Database version is not match, expect: ${VERSION}, actual: ${version}. recreate database.")
                        connection.close()
                        statement.close()
                        if (isRecreate) {
                            logger.warn("database already recreated, but version is not match, may be fatal problem.")
                        } else {
                            recreateDatabase()
                        }
                        return
                    }
                }
            }

            // Create a new table
            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS source_dirs (
                    path TEXT NOT NULL PRIMARY KEY
                );
                
                CREATE TABLE IF NOT EXISTS file_infos (
                    path TEXT NOT NULL PRIMARY KEY,
                    source_dir_path TEXT NOT NULL,
                    name TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS file_infos_name_index ON file_infos(name);
                
                PRAGMA schema_version = $VERSION;
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        logger.debug("Init database ${dbFile.name} success.")
    }

    @Synchronized
    fun updateSourceDirs(sourceDirs: List<File>) {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false

            val currentSourceDirs = mutableSetOf<File>()
            runWithTimeCost("doSelectSourceDirs") {
                val selectSQL = "SELECT path FROM source_dirs"
                connection.createStatement().use { statement ->
                    statement.executeQuery(selectSQL).use { resultSet ->
                        while (resultSet.next()) {
                            currentSourceDirs.add(File(resultSet.getString("path")))
                        }
                    }
                }
            }

            val relativeSourceDirs = sourceDirs.map { it.relativeTo(projectDir) }
            val sourceDirsSet = relativeSourceDirs.toSet()
            val addDirs = sourceDirsSet - currentSourceDirs
            val deleteDirs = currentSourceDirs - sourceDirsSet
            logger.debug("currentSourceDirs: ${currentSourceDirs.size}, addDirs: ${addDirs.size}, deleteDirs: ${deleteDirs.size}")

            runWithTimeCost("doDeleteSourceDirs") {
                val deleteSQL = "DELETE FROM source_dirs WHERE path = ?"
                connection.prepareStatement(deleteSQL).use { statement ->
                    deleteDirs.forEach { dir ->
                        statement.setString(1, dir.path)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }

            if (addDirs.isNotEmpty()) {
                runWithTimeCost("doInsertSourceDirs") {
                    val insertSQL = "INSERT INTO source_dirs(path) VALUES(?)"
                    connection.prepareStatement(insertSQL).use { statement ->
                        addDirs.forEach { dir ->
                            statement.setString(1, dir.path)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }

                val newFiles = runWithTimeCost("doGetNewFiles") {
                    addDirs.associateWith {
                        File(projectDir, it.path).listFilesRecursively().map { file ->
                            file.relativeTo(projectDir)
                        }
                    }
                }
                logger.debug("newFiles: ${newFiles.values.sumOf { it.size }}")

                runWithTimeCost("doInsertFiles") {
                    val insertSQL = "INSERT OR REPLACE INTO file_infos(source_dir_path, path, name) VALUES(?, ?, ?)"
                    connection.prepareStatement(insertSQL).use { statement ->
                        newFiles.forEach { (dir, files) ->
                            files.forEach { file ->
                                statement.setString(1, dir.path)
                                statement.setString(2, file.path)
                                statement.setString(3, file.name)
                                statement.addBatch()
                            }
                        }
                        statement.executeBatch()
                    }
                }
            }

            if (deleteDirs.isNotEmpty()) {
                runWithTimeCost("doDeleteFiles") {
                    val deleteSQL = "DELETE FROM file_infos WHERE source_dir_path = ?"
                    connection.prepareStatement(deleteSQL).use { statement ->
                        deleteDirs.forEach { dir ->
                            statement.setString(1, dir.path)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }
            }

            connection.commit()
        }
    }

    @Synchronized
    fun updateFiles(addFiles: List<ChangedFile>, deleteFiles: List<File>) {
        DriverManager.getConnection(url).use { connection ->

            runWithTimeCost("doInsertFiles") {
                val insertSQL = "INSERT OR REPLACE INTO file_infos(source_dir_path, path, name) VALUES(?, ?, ?)"
                connection.prepareStatement(insertSQL).use { statement ->
                    addFiles.forEach { file ->
                        statement.setString(1, file.baseDir.relativeTo(projectDir).path)
                        statement.setString(2, file.file.relativeTo(projectDir).path)
                        statement.setString(3, file.file.name)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }

            runWithTimeCost("doDeleteFiles") {
                val deleteSQL = "DELETE FROM file_infos WHERE path = ?"
                connection.prepareStatement(deleteSQL).use { statement ->
                    deleteFiles.forEach { file ->
                        statement.setString(1, file.relativeTo(projectDir).path)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    @Synchronized
    fun getFiles(fileNames: List<String>? = null): List<File> {
        DriverManager.getConnection(url).use { connection ->
            val selectSQL = if (fileNames == null) {
                "SELECT path FROM file_infos"
            } else {
                val fileNamesString = fileNames.joinToString(",") { "'$it'" }
                "SELECT path FROM file_infos WHERE name IN ($fileNamesString)"
            }

            val files = mutableListOf<File>()
            connection.createStatement().use { statement ->
                statement.executeQuery(selectSQL).use { resultSet ->
                    while (resultSet.next()) {
                        val path = resultSet.getString("path")
                        files.add(File(projectDir, path).normalize())
                    }
                }
            }
            return files
        }
    }

    @Synchronized
    fun recreateDatabase() {
        dbFile.delete()
        init(isRecreate = true)
    }

    private inline fun <T, R> T.runWithTimeCost(name: String, block: T.() -> R): R {
        val startTime = System.currentTimeMillis()
        val result = block()
        val endTime = System.currentTimeMillis()
        val costTime = endTime - startTime
        if (costTime > 100) {
            logger.debug("SQLite run $name cost ${endTime - startTime}ms")
        }
        return result
    }
}
