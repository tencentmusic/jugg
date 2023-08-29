package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class ParsedApkDatabaseSqLiteHelper(dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    private var hasInit = false

    @Synchronized
    fun init() {
        if (hasInit) {
            return
        }
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
                    source TEXT,
                    entry_info_name TEXT NOT NULL,
                    id INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS method_info (
                    class_id INTEGER NOT NULL,
                    access INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    desc TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_info (
                    class_id INTEGER NOT NULL,
                    access INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS method_refs (
                    class_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    desc TEXT NOT NULL,
                    ref_class_id INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS field_refs (
                    class_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    ref_class_id INTEGER NOT NULL
                );
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        hasInit = true
        logger.debug("Init database success.")
    }

    @Synchronized
    fun saveParsedApk(parsedApk: ParsedApk) {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            try {
                doInsertApkInfo(connection, parsedApk)
                connection.commit()
                logger.debug("Insert apk info success.")
            } catch (e: Exception) {
                connection.rollback()
                logger.error("Insert apk info failed.", e)
            }
        }
    }

    @Synchronized
    fun diffApk(apkOverlays: ApkOverlays): ParsedApkDiffResult {

        DriverManager.getConnection(url).use { connection ->
            val apkInfoKeys = mutableListOf<String>()
            val selectApkSQL = "SELECT * FROM apk_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectApkSQL)
                while (resultSet.next()) {
                    val key = resultSet.getString("key")
                    apkInfoKeys.add(key)
                }
            }

            if (apkInfoKeys.contains(apkOverlays.apkInfo.apkInfoKey)) {
                return ParsedApkDiffResult(updatedApkInfos = 0)
            }

            val selectEntrySQL = "SELECT * FROM entry_info;"
            val dbDexFiles = mutableMapOf<String, JuggFileInfo>()
            val dbOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectEntrySQL)
                while (resultSet.next()) {
                    val name = resultSet.getString("name")
                    val checksum = resultSet.getLong("checksum")
                    val isDex = resultSet.getBoolean("is_dex")
                    if (isDex) {
                        dbDexFiles[name] = JuggFileInfo(name, checksum)
                    } else {
                        dbOverlayFiles[name] = JuggFileInfo(name, checksum)
                    }
                }
            }

            val addedOverlayFiles = mutableListOf<String>()
            val removedOverlayFiles = mutableListOf<String>()
            val updatedOverlayFiles = mutableListOf<String>()
            apkOverlays.overlayFiles.forEach { (name, fileInfo) ->
                if (!dbOverlayFiles.containsKey(name)) {
                    addedOverlayFiles.add(fileInfo.name)
                } else if (dbOverlayFiles[name]!!.checksum != fileInfo.checksum) {
                    updatedOverlayFiles.add(fileInfo.name)
                }
            }
            dbOverlayFiles.forEach { (name, fileInfo) ->
                if (!apkOverlays.overlayFiles.containsKey(name)) {
                    removedOverlayFiles.add(fileInfo.name)
                }
            }

            val addedDexFiles = mutableListOf<String>()
            val removedDexFiles = mutableListOf<String>()
            val updatedDexFiles = mutableListOf<String>()
            apkOverlays.dexFiles.forEach { (name, fileInfo) ->
                if (!dbDexFiles.containsKey(name)) {
                    addedDexFiles.add(fileInfo.name)
                } else if (dbDexFiles[name]!!.checksum != fileInfo.checksum) {
                    updatedDexFiles.add(fileInfo.name)
                }
            }
            dbDexFiles.forEach { (name, fileInfo) ->
                if (!apkOverlays.dexFiles.containsKey(name)) {
                    removedDexFiles.add(fileInfo.name)
                }
            }

            return ParsedApkDiffResult(
                updatedApkInfos = 1,
                addedOverlayFiles = addedOverlayFiles,
                removedOverlayFiles = removedOverlayFiles,
                updatedOverlayFiles = updatedOverlayFiles,
                addedDexFiles = addedDexFiles,
                removedDexFiles = removedDexFiles,
                updatedDexFiles = updatedDexFiles,
            )
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

        val dbClassNodeMap = mutableMapOf<String, DbClassNode>()
        parsedApk.classes.values.forEachIndexed { index, it ->
            dbClassNodeMap[it.className] = DbClassNode(
                dexFileName = it.dexFileName,
                className = it.className,
                classId = index,
                interfaceNames = it.interfaceNames,
                superClass = it.superClass,
                source = it.source,
            )
        }

        insertSQL = "INSERT INTO class_info(name, interface_names, super_name, source, entry_info_name, id) VALUES(?, ?, ?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            dbClassNodeMap.values.forEach {
                preparedStatement.setString(1, it.className)
                preparedStatement.setString(2, it.interfaceNames.joinToString(" "))
                preparedStatement.setString(3, it.superClass)
                preparedStatement.setString(4, it.source)
                preparedStatement.setString(5, it.dexFileName)
                preparedStatement.setInt(6, it.classId)
                preparedStatement.addBatch()
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO method_info(class_id, access, name, desc) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            dbClassNodeMap.values.forEach { dbClassNode ->
                val classNode = parsedApk.classes[dbClassNode.className]!!
                classNode.methods.forEach {
                    preparedStatement.setInt(1, dbClassNode.classId)
                    preparedStatement.setInt(2, it.access)
                    preparedStatement.setString(3, it.name)
                    preparedStatement.setString(4, it.desc)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO field_info(class_id, access, name, type) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            dbClassNodeMap.values.forEach { dbClassNode ->
                val classNode = parsedApk.classes[dbClassNode.className]!!
                classNode.fields.forEach {
                    preparedStatement.setInt(1, dbClassNode.classId)
                    preparedStatement.setInt(2, it.access)
                    preparedStatement.setString(3, it.name)
                    preparedStatement.setString(4, it.type)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO method_refs(class_id, name, desc, ref_class_id) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.methodRefs.forEach { (methodNode, refClasses) ->
                val dbClassNode = dbClassNodeMap[methodNode.owner]
                    ?: // The class of the field is not exists in the apk. Maybe in the android.jar. Skip it.
                    return@forEach
                refClasses.forEach {
                    preparedStatement.setInt(1, dbClassNode.classId)
                    preparedStatement.setString(2, methodNode.name)
                    preparedStatement.setString(3, methodNode.desc)
                    preparedStatement.setInt(4, dbClassNodeMap[it]!!.classId)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }

        insertSQL = "INSERT INTO field_refs(class_id, name, type, ref_class_id) VALUES(?, ?, ?, ?);"
        connection.prepareStatement(insertSQL).use { preparedStatement ->
            parsedApk.fieldRefs.forEach { (fieldNode, refClassIds) ->
                val dbClassNode = dbClassNodeMap[fieldNode.owner]
                    ?: // The class of the field is not exists in the apk. Maybe in the android.jar. Skip it.
                    return@forEach
                refClassIds.forEach {
                    preparedStatement.setInt(1, dbClassNode.classId)
                    preparedStatement.setString(2, fieldNode.name)
                    preparedStatement.setString(3, fieldNode.type)
                    preparedStatement.setInt(4, dbClassNodeMap[it]!!.classId)
                    preparedStatement.addBatch()
                }
            }
            preparedStatement.executeBatch()
        }
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

            val dbClasses = mutableMapOf<Int, DbClassNode>()
            val selectClassSQL = "SELECT * FROM class_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val interfaceNames = resultSet.getString(2).split(" ").toList()
                    val superName = resultSet.getString(3)
                    val source = resultSet.getString(4)
                    val dexFileName = resultSet.getString(5)
                    val id = resultSet.getInt(6)
                    val classNode = DbClassNode(dexFileName, className, id, interfaceNames, superName, source)
                    dbClasses[id] = classNode
                }
            }

            val classMethods = mutableMapOf<Int, MutableList<MethodNode>>()
            val selectMethodSQL = "SELECT * FROM method_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectMethodSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val methodAccess = resultSet.getInt(2)
                    val methodName = resultSet.getString(3)
                    val methodDesc = resultSet.getString(4)
                    val className = dbClasses[classId]?.className ?: continue
                    classMethods.getOrPut(classId) { mutableListOf() }.add(MethodNode(className, methodAccess, methodName, methodDesc))
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
                    val className = dbClasses[classId]?.className ?: continue
                    classFields.getOrPut(classId) { mutableListOf() }.add(FieldNode(className, access, fieldName, fieldType))
                }
            }


            val classes = mutableMapOf<String, ClassNode>()
            dbClasses.values.forEach {
                val methods = classMethods[it.classId] ?: emptyList()
                val fields = classFields[it.classId] ?: emptyList()
                classes[it.className] = ClassNode(it.dexFileName, it.className, methods, fields, it.interfaceNames, it.superClass, it.source)
            }

            val methodRefs = mutableMapOf<MethodNode, MutableList<String>>()
            val selectMethodRefSQL = "SELECT * FROM method_refs;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectMethodRefSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val methodName = resultSet.getString(2)
                    val methodDesc = resultSet.getString(3)
                    val refClassId = resultSet.getInt(4)
                    val className = dbClasses[classId]?.className ?: continue
                    val refClassName = dbClasses[refClassId]?.className ?: continue
                    val methodNode = MethodNode(className, MethodNode.MISS_ACCESS, methodName, methodDesc)
                    methodRefs.getOrPut(methodNode) { mutableListOf() }.add(refClassName)
                }
            }

            val fieldRefs = mutableMapOf<FieldNode, MutableList<String>>()
            val selectFieldRefSQL = "SELECT * FROM field_refs;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectFieldRefSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val fieldName = resultSet.getString(2)
                    val fieldType = resultSet.getString(3)
                    val refClassId = resultSet.getInt(4)
                    val className = dbClasses[classId]?.className ?: continue
                    val refClassName = dbClasses[refClassId]?.className ?: continue
                    val fieldNode = FieldNode(className, FieldNode.MISS_ACCESS, fieldName, fieldType)
                    fieldRefs.getOrPut(fieldNode) { mutableListOf() }.add(refClassName)
                }
            }

            return ParsedApk(apkInfo, classes, dexFiles, overlayFiles, methodRefs, fieldRefs)
        }
    }

    @Synchronized
    fun getOverlayInfos(): List<JuggFileInfo> {
        val selectSQL = "SELECT * FROM entry_info WHERE is_dex = false;"
        val overlayInfos = mutableListOf<JuggFileInfo>()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSQL)
                while (resultSet.next()) {
                    val overlayInfo = JuggFileInfo(
                        resultSet.getString("name"),
                        resultSet.getLong("checksum"),
                    )
                    overlayInfos.add(overlayInfo)
                }
            }
        }
        return overlayInfos
    }

    @Synchronized
    fun getClassNodes(classNames: List<String>): Map<String, ClassNode> {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val classNamesString = classNames.joinToString(",") { "'$it'" }
                val selectClassSQL = "SELECT * FROM class_info WHERE class_name IN ($classNamesString);"
                val dbClasses = mutableMapOf<Int, DbClassNode>()
                var resultSet: ResultSet = statement.executeQuery(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val interfaceNames = resultSet.getString(2).split(" ").toList()
                    val superName = resultSet.getString(3)
                    val source = resultSet.getString(4)
                    val dexFileName = resultSet.getString(5)
                    val id = resultSet.getInt(6)
                    val classNode = DbClassNode(dexFileName, className, id, interfaceNames, superName, source)
                    dbClasses[id] = classNode
                }
                val classIds = dbClasses.keys.joinToString(",")

                val classMethods = mutableMapOf<Int, MutableList<MethodNode>>()
                val selectMethodSQL = "SELECT * FROM method_info WHERE class_id IN ($classIds);"
                resultSet = statement.executeQuery(selectMethodSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val methodAccess = resultSet.getInt(2)
                    val methodName = resultSet.getString(3)
                    val methodDesc = resultSet.getString(4)
                    val className = dbClasses[classId]?.className ?: continue
                    classMethods.getOrPut(classId) { mutableListOf() }.add(MethodNode(className, methodAccess, methodName, methodDesc))
                }

                val classFields = mutableMapOf<Int, MutableList<FieldNode>>()
                val selectFieldSQL = "SELECT * FROM field_info WHERE class_id IN ($classIds);"
                resultSet = statement.executeQuery(selectFieldSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val access = resultSet.getInt(2)
                    val fieldName = resultSet.getString(3)
                    val fieldType = resultSet.getString(4)
                    val className = dbClasses[classId]?.className ?: continue
                    classFields.getOrPut(classId) { mutableListOf() }.add(FieldNode(className, access, fieldName, fieldType))
                }

                val classes = mutableMapOf<String, ClassNode>()
                dbClasses.values.forEach {
                    val methods = classMethods[it.classId] ?: emptyList()
                    val fields = classFields[it.classId] ?: emptyList()
                    classes[it.className] = ClassNode(it.dexFileName, it.className, methods, fields, it.interfaceNames, it.superClass, it.source)
                }

                return classes
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

private class DbClassNode(
    val dexFileName: String,
    val className: String,
    val classId: Int,
    val interfaceNames: List<String>,
    val superClass: String,
    val source: String?,
)