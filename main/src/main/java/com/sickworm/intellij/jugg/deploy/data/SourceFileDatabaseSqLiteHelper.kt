package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import java.io.File
import java.sql.DriverManager

class SourceFileDatabaseSqLiteHelper(private val dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    private var hasInit = false

    @Synchronized
    fun init() {
        if (hasInit) {
            return
        }
        SqLiteDriverLoader.load(logger)
        dbFile.parentFile?.mkdirs()

        // Create a new database connection
        DriverManager.getConnection(url).use { connection ->
            // Create a new table
            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS source_dirs (
                    path TEXT NOT NULL PRIMARY KEY
                );
                
                CREATE TABLE IF NOT EXISTS file_infos (
                    path TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS file_infos_name_index ON file_infos(name);
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        hasInit = true
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

            val sourceDirsSet = sourceDirs.toSet()
            val deleteDirs = currentSourceDirs - sourceDirsSet
            val addDirs = sourceDirsSet - currentSourceDirs
            logger.debug("currentSourceDirs: ${currentSourceDirs.size}, deleteDirs: ${deleteDirs.size}, addDirs: ${addDirs.size}")

            runWithTimeCost("doDeleteSourceDirs") {
                val deleteSQL = "DELETE FROM source_dirs WHERE path = ?"
                connection.prepareStatement(deleteSQL).use { statement ->
                    deleteDirs.forEach { dir ->
                        statement.setString(1, dir.absolutePath)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }

            runWithTimeCost("doInsertSourceDirs") {
                val insertSQL = "INSERT INTO source_dirs(path) VALUES(?)"
                connection.prepareStatement(insertSQL).use { statement ->
                    addDirs.forEach { dir ->
                        statement.setString(1, dir.absolutePath)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }

            val newFiles = runWithTimeCost("doGetNewFiles") {
                addDirs.flatMap {
                    it.listFilesRecursively()
                }
            }
            logger.debug("newFiles: ${newFiles.size}")

            runWithTimeCost("doInsertFiles") {
                val insertSQL = "INSERT INTO file_infos(path, name) VALUES(?, ?)"
                connection.prepareStatement(insertSQL).use { statement ->
                    newFiles.forEach { file ->
                        statement.setString(1, file.absolutePath)
                        statement.setString(2, file.name)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }

            connection.commit()
        }
    }

    @Synchronized
    fun updateFiles(addFiles: List<File>, deleteFiles: List<File>) {
        DriverManager.getConnection(url).use { connection ->

            runWithTimeCost("doInsertFiles") {
                val insertSQL = "INSERT OR REPLACE INTO file_infos(path, name) VALUES(?, ?)"
                connection.prepareStatement(insertSQL).use { statement ->
                    addFiles.forEach { file ->
                        statement.setString(1, file.absolutePath)
                        statement.setString(2, file.name)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }

            runWithTimeCost("doDeleteFiles") {
                val deleteSQL = "DELETE FROM file_infos WHERE path = ?"
                connection.prepareStatement(deleteSQL).use { statement ->
                    deleteFiles.forEach { file ->
                        statement.setString(1, file.absolutePath)
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
                        files.add(File(path))
                    }
                }
            }
            return files
        }
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