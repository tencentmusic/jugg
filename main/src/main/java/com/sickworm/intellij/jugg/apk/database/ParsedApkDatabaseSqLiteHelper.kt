package com.sickworm.intellij.jugg.apk.database

import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet

class ParsedApkDatabaseSqLiteHelper(dbFile: File) {

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
                    checksum INTEGER NOT NULL,
                    apk_info_key TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS class_info (
                    name TEXT NOT NULL PRIMARY KEY,
                    interface_names TEXT NOT NULL,
                    super_name TEXT NOT NULL,
                    entry_info_name TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS method_info (
                    signature TEXT NOT NULL PRIMARY KEY,
                    class_name INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_method_info_class_name ON method_info (class_name);
                CREATE TABLE IF NOT EXISTS field_info (
                    signature TEXT NOT NULL PRIMARY KEY,
                    class_name INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_field_info_class_name ON field_info (class_name);
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }
    }

    fun insertApkInfo(key: String) {
        val insertSQL = """
            INSERT INTO apk_info(key) VALUES(?);
        """.trimIndent()
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement(insertSQL).use { preparedStatement ->
                preparedStatement.setString(1, key)
                preparedStatement.executeUpdate()
            }
        }
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
}