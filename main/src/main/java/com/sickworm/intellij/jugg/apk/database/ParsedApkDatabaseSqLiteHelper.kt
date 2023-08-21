package com.sickworm.intellij.jugg.apk.database

import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.statement
import com.sickworm.intellij.jugg.compiler.ParsedApk
import org.sqlite.SQLiteException
import org.sqlite.core.CorePreparedStatement
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class ParsedApkDatabaseSqLiteHelper(dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    @Synchronized
    fun init() {
        // Create a new database connection
        DriverManager.getConnection(url).use { connection ->
            // Create a new table
            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS apk_info (
                    key TEXT NOT NULL PRIMARY KEY
                );
                CREATE TABLE IF NOT EXISTS entry_info (
                    name TEXT NOT NULL PRIMARY KEY,
                    checksum INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS class_info (
                    name TEXT NOT NULL PRIMARY KEY,
                    interface_names TEXT NOT NULL,
                    super_name TEXT NOT NULL,
                    entry_info_name TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS method_info (
                    id INTEGER PRIMARY KEY,
                    class_name TEXT NOT NULL,
                    signature TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_info (
                    id INTEGER PRIMARY KEY,
                    class_name TEXT NOT NULL,
                    signature TEXT NOT NULL
                );
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        logger.debug("Init database success.")
    }

    @Synchronized
    fun insertApkInfo(parsedApks: List<ParsedApk>) {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            parsedApks.forEach { parsedApk ->
                try {
                    doInsertApkInfo(connection, parsedApk)
                    logger.debug("Insert apk info success.")
                } catch (e: Exception) {
                    connection.rollback()
                    logger.error("Insert apk info failed.", e)
                }
            }
        }
    }

    private fun doInsertApkInfo(connection: Connection, parsedApk: ParsedApk) {
        var insertSQL = "INSERT INTO apk_info(key) VALUES(?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            preparedStatement.setString(1, parsedApk.apkInfoKey)
            preparedStatement.executeUpdate()
        }

        insertSQL = "INSERT INTO entry_info(name, checksum) VALUES(?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.dexFiles.values.forEach {
                preparedStatement.setString(1, it.name)
                preparedStatement.setLong(2, it.checksum)
                preparedStatement.addBatch()
            }
            parsedApk.overlayFiles.values.forEach {
                preparedStatement.setString(1, it.name)
                preparedStatement.setLong(2, it.checksum)
                preparedStatement.addBatch()
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO class_info(name, interface_names, super_name, entry_info_name) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->

            parsedApk.classes.values.forEach {
                preparedStatement.setString(1, it.className)
                preparedStatement.setString(2, it.interfaceNames.joinToString(" "))
                preparedStatement.setString(3, it.superClass)
                preparedStatement.setString(4, it.dexFileName)
                preparedStatement.addBatch()
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO method_info(signature, class_name) VALUES(?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.classes.values.forEach { classNode ->
                classNode.methods.forEach {
                    preparedStatement.setString(1, it.signature)
                    preparedStatement.setString(2, classNode.className)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO field_info(signature, class_name) VALUES(?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.classes.values.forEach { classNode ->
                classNode.fields.forEach {
                    preparedStatement.setString(1, it.signature)
                    preparedStatement.setString(2, classNode.className)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        connection.commit()
    }

    @Synchronized
    fun getApkInfoKeys(): List<String> {
        val selectSQL = "SELECT * FROM apk_info;"
        val keys = mutableListOf<String>()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSQL)
                while (resultSet.next()) {
                    val key = resultSet.getString("key")
                    keys.add(key)
                }
            }
        }
        return keys
    }

    @Synchronized
    fun getSize(tableName: String): Int {
        val selectSQL = "SELECT COUNT(*) FROM $tableName;"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSQL)
                while (resultSet.next()) {
                    return resultSet.getInt(1)
                }
            }
        }
        return -1
    }
}