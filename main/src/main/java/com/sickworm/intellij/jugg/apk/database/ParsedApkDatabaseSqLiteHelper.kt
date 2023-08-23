package com.sickworm.intellij.jugg.apk.database

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.JuggFileInfo
import com.sickworm.intellij.jugg.compiler.*
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
                    checksum INTEGER NOT NULL,
                    is_dex BOOL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS class_info (
                    name TEXT NOT NULL PRIMARY KEY,
                    interface_names TEXT NOT NULL,
                    super_name TEXT NOT NULL,
                    entry_info_name TEXT NOT NULL,
                    id INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS method_info (
                    class_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    desc TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_info (
                    class_id INTEGER NOT NULL,
                    access INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL
                );
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        logger.debug("Init database success.")
    }

    @Synchronized
    fun saveParsedApk(parsedApk: ParsedApk) {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            try {
                doInsertApkInfo(connection, parsedApk)
                logger.debug("Insert apk info success.")
            } catch (e: Exception) {
                connection.rollback()
                logger.error("Insert apk info failed.", e)
            }
        }
    }

    private fun doInsertApkInfo(connection: Connection, parsedApk: ParsedApk) {
        var insertSQL = "INSERT INTO apk_info(key) VALUES(?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            preparedStatement.setString(1, parsedApk.apkInfo.apkInfoKey)
            preparedStatement.executeUpdate()
        }

        insertSQL = "INSERT INTO entry_info(name, checksum, is_dex) VALUES(?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.dexFiles.values.forEach {
                preparedStatement.setString(1, it.name)
                preparedStatement.setLong(2, it.checksum)
                preparedStatement.setBoolean(3, true)
                preparedStatement.addBatch()
            }
            parsedApk.overlayFiles.values.forEach {
                preparedStatement.setString(1, it.name)
                preparedStatement.setLong(2, it.checksum)
                preparedStatement.setBoolean(3, false)
                preparedStatement.addBatch()
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO class_info(name, interface_names, super_name, entry_info_name, id) VALUES(?, ?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->

            parsedApk.classes.values.forEachIndexed { index, it ->
                preparedStatement.setString(1, it.className)
                preparedStatement.setString(2, it.interfaceNames.joinToString(" "))
                preparedStatement.setString(3, it.superClass)
                preparedStatement.setString(4, it.dexFileName)
                preparedStatement.setInt(5, index)
                preparedStatement.addBatch()
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO method_info(class_id, name, desc) VALUES(?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.classes.values.forEachIndexed { index, classNode ->
                classNode.methods.forEach {
                    preparedStatement.setInt(1, index)
                    preparedStatement.setString(2, it.name)
                    preparedStatement.setString(3, it.desc)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO field_info(class_id, access, name, type) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.classes.values.forEachIndexed { index, classNode ->
                classNode.fields.forEach {
                    preparedStatement.setInt(1, index)
                    preparedStatement.setInt(2, it.access)
                    preparedStatement.setString(3, it.name)
                    preparedStatement.setString(4, it.type)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        connection.commit()
    }

    @Synchronized
    fun getParsedApk(apkInfo: ApkInfo): ParsedApk? {
        val apkInfoKeys = getApkInfoKeys()
        if (apkInfoKeys.size != 1) {
            logger.warn("Apk info key size is not 1.")
            return null
        }
        val apkInfoKey = apkInfoKeys[0]
        if (apkInfoKey != apkInfo.apkInfoKey) {
            logger.warn("Apk info key is not match. expect: ${apkInfo.apkInfoKey}, actual: $apkInfoKey")
            return null
        }

        DriverManager.getConnection(url).use { connection ->
            val dexFiles = mutableMapOf<String, JuggFileInfo>()
            val overlayFiles = mutableMapOf<String, JuggFileInfo>()

            val selectSQL = "SELECT * FROM entry_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSQL)
                while (resultSet.next()) {
                    val fileName = resultSet.getString(1)
                    val checksum = resultSet.getLong(2)
                    val isDex = resultSet.getBoolean(3)
                    if (isDex) {
                        dexFiles[fileName] = JuggFileInfo(fileName, checksum)
                    } else {
                        overlayFiles[fileName] = JuggFileInfo(fileName, checksum)
                    }
                }
            }

            val classMethods = mutableMapOf<Int, MutableList<MethodNode>>()
            val selectMethodSQL = "SELECT * FROM method_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectMethodSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val methodName = resultSet.getString(2)
                    val methodDesc = resultSet.getString(3)
                    classMethods.getOrPut(classId) { mutableListOf() }.add(MethodNode(methodName, methodDesc))
                }
            }

            val classFields = mutableMapOf<Int, MutableList<FieldNode>>()
            val selectFieldSQL = "SELECT * FROM field_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectFieldSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val access = resultSet.getInt(2)
                    val fieldName = resultSet.getString(3)
                    val fieldType = resultSet.getString(4)
                    classFields.getOrPut(classId) { mutableListOf() }.add(FieldNode(access, fieldName, fieldType))
                }
            }

            val classes = mutableMapOf<String, ClassNode>()
            val selectClassSQL = "SELECT * FROM class_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val interfaceNames = resultSet.getString(2).split(" ").toList()
                    val superName = resultSet.getString(3)
                    val dexFileName = resultSet.getString(4)
                    val id = resultSet.getInt(5)
                    val methods = classMethods[id] ?: emptyList()
                    val fields = classFields[id] ?: emptyList()
                    val classNode = ClassNode(dexFileName, className, methods, fields, interfaceNames, superName, null)
                    classes[className] = classNode
                }
            }

            return ParsedApk(apkInfo, classes, dexFiles, overlayFiles)
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