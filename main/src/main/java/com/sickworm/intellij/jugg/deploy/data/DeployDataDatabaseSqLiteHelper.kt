package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.math.max


class DeployDataDatabaseSqLiteHelper(private val dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    private var hasInit = false

    @Synchronized
    fun init() {
        if (hasInit) {
            return
        }
        dbFile.parentFile?.mkdirs()

        // Create a new database connection
        DriverManager.getConnection(url).use { connection ->
            // Create a new table
            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS apk_info (
                    key TEXT NOT NULL PRIMARY KEY,
                    next_class_id INTEGER NOT NULL
                );
                
                CREATE TABLE IF NOT EXISTS entry_info (
                    name TEXT NOT NULL PRIMARY KEY,
                    checksum INTEGER NOT NULL,
                    is_dex BOOL NOT NULL
                );
                
                CREATE TABLE IF NOT EXISTS class_info (
                    name TEXT NOT NULL,
                    interface_names TEXT NOT NULL,
                    super_name TEXT NOT NULL,
                    source TEXT,
                    entry_info_name TEXT NOT NULL,
                    id INTEGER NOT NULL PRIMARY KEY
                );
                
                CREATE TABLE IF NOT EXISTS method_info (
                    class_id INTEGER NOT NULL,
                    access INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    desc TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS method_info_class_id_index ON method_info(class_id);
                
                CREATE TABLE IF NOT EXISTS field_info (
                    class_id INTEGER NOT NULL,
                    access INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS field_info_class_id_index ON field_info(class_id);
                
                CREATE TABLE IF NOT EXISTS method_refs (
                    class_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    desc TEXT NOT NULL,
                    ref_class_id INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS method_refs_class_id_index ON method_refs(class_id);
                CREATE INDEX IF NOT EXISTS method_refs_ref_class_id_index ON method_refs(ref_class_id);
                
                CREATE TABLE IF NOT EXISTS field_refs (
                    class_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    ref_class_id INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS field_refs_class_id_index ON field_refs(class_id);
                CREATE INDEX IF NOT EXISTS field_refs_ref_class_id_index ON field_refs(ref_class_id);
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        hasInit = true
        logger.debug("Init database success.")
    }

    @Synchronized
    fun saveParsedApk(parsedApk: ParsedApk, apkDiffResult: ParsedApkDiffResult): ParsedApkUpdateResult {
        DriverManager.getConnection(url).use { connection ->
            val startTime = System.currentTimeMillis()
            connection.autoCommit = false
            try {
                val result = doInsertApkInfo(connection, parsedApk, apkDiffResult)
                connection.commit()
                logger.debug("saveParsedApk success. cost ${System.currentTimeMillis() - startTime}ms.")
                return result
            } catch (e: Exception) {
                connection.rollback()
                logger.error("saveParsedApk failed. cost ${System.currentTimeMillis() - startTime}ms.", e)
                return ParsedApkUpdateResult.failed(apkDiffResult, e.message)
            }
        }
    }

    private fun doInsertApkInfo(connection: Connection,
                                parsedApk: ParsedApk,
                                apkDiffResult: ParsedApkDiffResult): ParsedApkUpdateResult {
        if (apkDiffResult.updatedApkInfos == 0) {
            return ParsedApkUpdateResult.success(apkDiffResult)
        }

        var nextClassId = 1
        runWithTimeCost("doInsertApkInfo") {
            val querySql = "SELECT next_class_id FROM apk_info;"
            connection.createStatement().use { preparedStatement ->
                val resultSet: ResultSet = preparedStatement.executeQuery(querySql)
                while (resultSet.next()) {
                    nextClassId = max(nextClassId, resultSet.getInt(1))
                }
            }
            val deleteSql = "DELETE FROM apk_info;"
            connection.createStatement().use { preparedStatement ->
                preparedStatement.executeUpdate(deleteSql)
            }
            val sql = "INSERT INTO apk_info(key, next_class_id) VALUES(?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                preparedStatement.setString(1, parsedApk.apkInfo.apkInfoKey)
                preparedStatement.setInt(2, nextClassId)
                preparedStatement.executeUpdate()
            }
        }


        runWithTimeCost("doDeleteEntryInfo") {
            val removedDexFiles = apkDiffResult.removedDexFiles + apkDiffResult.updatedDexFiles
            val removedOverlayFiles = apkDiffResult.removedOverlayFiles + apkDiffResult.updatedOverlayFiles
            if (removedDexFiles.isEmpty() && removedOverlayFiles.isEmpty()) {
                return@runWithTimeCost
            }

            val sql = "DELETE FROM entry_info WHERE name=?;"
            connection.prepareStatement(sql).use { preparedStatement ->
                removedDexFiles.values.forEach {
                    preparedStatement.setString(1, it.name)
                    preparedStatement.addBatch()
                }
                removedOverlayFiles.values.forEach {
                    preparedStatement.setString(1, it.name)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }
        }

        runWithTimeCost("doInsertEntryInfo") {
            val addedDexFiles = apkDiffResult.addedDexFiles + apkDiffResult.updatedDexFiles
            val addedOverlayFiles = apkDiffResult.addedOverlayFiles + apkDiffResult.updatedOverlayFiles
            if (addedDexFiles.isEmpty() && addedOverlayFiles.isEmpty()) {
                return@runWithTimeCost
            }

            val sql = "INSERT INTO entry_info(name, checksum, is_dex) VALUES(?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                addedDexFiles.values.forEach {
                    preparedStatement.setString(1, it.name)
                    preparedStatement.setLong(2, it.checksum)
                    preparedStatement.setBoolean(3, true)
                    preparedStatement.addBatch()
                }
                addedOverlayFiles.values.forEach {
                    preparedStatement.setString(1, it.name)
                    preparedStatement.setLong(2, it.checksum)
                    preparedStatement.setBoolean(3, false)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }
        }

        val dbClassNodeMap = mutableMapOf<String, Int>() // class name -> id map
        val removedClasses = mutableMapOf<String, Int>()
        val updatedClasses = mutableMapOf<String, Int>()

        runWithTimeCost("doGetClassInfo") {
            val deleteDexNames = apkDiffResult.removedDexFiles + apkDiffResult.updatedDexFiles
            if (deleteDexNames.isEmpty()) {
                return@runWithTimeCost
            }

            val deleteDexNamesString = deleteDexNames.keys.joinToString(",") { "'$it'"}
            val refClassNames = parsedApk.methodRefs.values.flatten() + parsedApk.fieldRefs.values.flatten()
            val refClassNamesString = refClassNames.joinToString(",") { "'$it'"}
            val selectClassSQL = if (deleteDexNames.size + refClassNames.size > 10000) {
                // query performance optimize
                "SELECT name, entry_info_name, id FROM class_info;"
            } else {
                "SELECT name, entry_info_name, id FROM class_info WHERE (entry_info_name IN ($deleteDexNamesString)) OR (name IN ($refClassNamesString));"
            }
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val entryName = resultSet.getString(2)
                    val id = resultSet.getInt(3)
                    dbClassNodeMap[className] = id

                    if (apkDiffResult.removedDexFiles.containsKey(entryName)) {
                        removedClasses[className] = id
                    } else if (apkDiffResult.updatedDexFiles.containsKey(entryName)) {
                        updatedClasses[className] = id
                    }
                }
            }
        }

        runWithTimeCost("doDeleteAllClassInfo") {

            val dbDeleteClasses = removedClasses + updatedClasses
            if (dbDeleteClasses.isEmpty()) {
                return@runWithTimeCost
            }

            val deleteClassSql = "DELETE FROM class_info WHERE id=?;"
            connection.prepareStatement(deleteClassSql).use { preparedStatement ->
                dbDeleteClasses.values.forEach {
                    preparedStatement.setInt(1, it)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }

            val deleteMethodSql = "DELETE FROM method_info WHERE class_id=?;"
            connection.prepareStatement(deleteMethodSql).use { preparedStatement ->
                dbDeleteClasses.values.forEach {
                    preparedStatement.setInt(1, it)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }

            val deleteFieldSql = "DELETE FROM field_info WHERE class_id=?;"
            connection.prepareStatement(deleteFieldSql).use { preparedStatement ->
                dbDeleteClasses.values.forEach {
                    preparedStatement.setInt(1, it)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }

            val deleteMethodRefSql = "DELETE FROM method_refs WHERE class_id=? OR ref_class_id=?;"
            connection.prepareStatement(deleteMethodRefSql).use { preparedStatement ->
                dbDeleteClasses.values.forEach {
                    preparedStatement.setInt(1, it)
                    preparedStatement.setInt(2, it)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }

            val deleteFieldRefSql = "DELETE FROM field_refs WHERE class_id=? OR ref_class_id=?;"
            connection.prepareStatement(deleteFieldRefSql).use { preparedStatement ->
                dbDeleteClasses.values.forEach {
                    preparedStatement.setInt(1, it)
                    preparedStatement.setInt(2, it)
                    preparedStatement.addBatch()
                }
                preparedStatement.executeBatch()
            }
        }

        val addedClasses = parsedApk.classes.keys.filter {
            !updatedClasses.containsKey(it)
        }

        runWithTimeCost("doInsertClassInfo") {
            val sql = "INSERT INTO class_info(name, interface_names, super_name, source, entry_info_name, id) VALUES(?, ?, ?, ?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.classes.values.forEach {
                    preparedStatement.setString(1, it.className)
                    preparedStatement.setString(2, it.interfaceNames.joinToString(" "))
                    preparedStatement.setString(3, it.superClass)
                    preparedStatement.setString(4, it.source)
                    preparedStatement.setString(5, it.dexFileName)
                    val classId = nextClassId++
                    preparedStatement.setInt(6, classId)
                    preparedStatement.addBatch()
                    dbClassNodeMap[it.className] = classId
                }
                preparedStatement.executeBatch()
            }
        }

        runWithTimeCost("doInsertMethodInfo") {
            val sql = "INSERT INTO method_info(class_id, access, name, desc) VALUES(?, ?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.classes.values.forEach { classNode ->
                    classNode.methods.forEach {
                        preparedStatement.setInt(1, dbClassNodeMap[classNode.className]!!)
                        preparedStatement.setInt(2, it.access)
                        preparedStatement.setString(3, it.name)
                        preparedStatement.setString(4, it.desc)
                        preparedStatement.addBatch()
                    }
                }
                preparedStatement.executeBatch()
            }
        }
        
        runWithTimeCost("doInsertFieldInfo") {
            val sql = "INSERT INTO field_info(class_id, access, name, type) VALUES(?, ?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.classes.values.forEach { classNode ->
                    classNode.fields.forEach {
                        preparedStatement.setInt(1, dbClassNodeMap[classNode.className]!!)
                        preparedStatement.setInt(2, it.access)
                        preparedStatement.setString(3, it.name)
                        preparedStatement.setString(4, it.type)
                        preparedStatement.addBatch()
                    }
                }
                preparedStatement.executeBatch()
            }
        }

        runWithTimeCost("doInsertMethodRef") {
            val sql = "INSERT INTO method_refs(class_id, name, desc, ref_class_id) VALUES(?, ?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.methodRefs.forEach { (methodNode, refClasses) ->
                    val dbClassNode = dbClassNodeMap[methodNode.owner]
                        ?: // The class of the field is not exists in the apk. Maybe in the android.jar. Skip it.
                        return@forEach
                    refClasses.forEach {
                        preparedStatement.setInt(1, dbClassNode)
                        preparedStatement.setString(2, methodNode.name)
                        preparedStatement.setString(3, methodNode.desc)
                        preparedStatement.setInt(4, dbClassNodeMap[it]!!)
                        preparedStatement.addBatch()
                    }
                }
                preparedStatement.executeBatch()
            }
        }

        runWithTimeCost("doInsertFieldRef") {
            val sql = "INSERT INTO field_refs(class_id, name, type, ref_class_id) VALUES(?, ?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.fieldRefs.forEach { (fieldNode, refClassIds) ->
                    val dbClassNode = dbClassNodeMap[fieldNode.owner]
                        ?: // The class of the field is not exists in the apk. Maybe in the android.jar. Skip it.
                        return@forEach
                    refClassIds.forEach {
                        preparedStatement.setInt(1, dbClassNode)
                        preparedStatement.setString(2, fieldNode.name)
                        preparedStatement.setString(3, fieldNode.type)
                        preparedStatement.setInt(4, dbClassNodeMap[it]!!)
                        preparedStatement.addBatch()
                    }
                }
                preparedStatement.executeBatch()
            }
        }

        runWithTimeCost("dpUpdateNextClassId") {
            val sql = "UPDATE apk_info SET next_class_id=?;"
            connection.prepareStatement(sql).use { preparedStatement ->
                preparedStatement.setInt(1, nextClassId)
                preparedStatement.execute()
            }
        }

        return ParsedApkUpdateResult.success(apkDiffResult).copy(
            addedClasses = addedClasses,
            removedClasses = removedClasses.keys.toList(),
            updatedClasses = updatedClasses.keys.toList(),
        )
    }

    @Synchronized
    fun diffApk(apkEntries: ApkEntries): ParsedApkDiffResult {

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

            if (apkInfoKeys.contains(apkEntries.apkInfo.apkInfoKey)) {
                return ParsedApkDiffResult(apkEntries.apkInfo, updatedApkInfos = 0)
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

            val addedOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            val removedOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            val updatedOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            apkEntries.overlayFiles.forEach { (name, fileInfo) ->
                if (!dbOverlayFiles.containsKey(name)) {
                    addedOverlayFiles[name] = fileInfo
                } else if (dbOverlayFiles[name]!!.checksum != fileInfo.checksum) {
                    updatedOverlayFiles[name] = fileInfo
                }
            }
            dbOverlayFiles.forEach { (name, fileInfo) ->
                if (!apkEntries.overlayFiles.containsKey(name)) {
                    removedOverlayFiles[name] = fileInfo
                }
            }

            val addedDexFiles = mutableMapOf<String, JuggFileInfo>()
            val removedDexFiles = mutableMapOf<String, JuggFileInfo>()
            val updatedDexFiles = mutableMapOf<String, JuggFileInfo>()
            apkEntries.dexFiles.forEach { (name, fileInfo) ->
                if (!dbDexFiles.containsKey(name)) {
                    addedDexFiles[name] = fileInfo
                } else if (dbDexFiles[name]!!.checksum != fileInfo.checksum) {
                    updatedDexFiles[name] = fileInfo
                }
            }
            dbDexFiles.forEach { (name, fileInfo) ->
                if (!apkEntries.dexFiles.containsKey(name)) {
                    removedDexFiles[name] = fileInfo
                }
            }

            return ParsedApkDiffResult(
                apkEntries.apkInfo,
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
    fun getEffectedClassNodes(changedMethodRefs: List<MethodNode>, changedFieldRefs: List<FieldNode>): Map<String, List<String>> {
        DriverManager.getConnection(url).use { connection ->

            val dbClassNodeMap = mutableMapOf<String, Int>()
            runWithTimeCost("doGetClassIds") {
                val classNameList = mutableListOf<String>()
                changedMethodRefs.forEach { classNameList.add(it.owner) }
                changedFieldRefs.forEach { classNameList.add(it.owner) }
                val classNamesString = classNameList.joinToString(",") { "'$it'" }

                val sql = "SELECT name, id FROM class_info WHERE id IN ($classNamesString);"
                connection.createStatement().use { statement ->
                    val resultSet: ResultSet = statement.executeQuery(sql)
                    while (resultSet.next()) {
                        val className = resultSet.getString(1)
                        val classId = resultSet.getInt(2)
                        dbClassNodeMap[className] = classId
                    }
                }
            }

            val refClassIds = mutableSetOf<Int>()
            runWithTimeCost("doGetRefClassIds") {
                if (changedMethodRefs.isNotEmpty()) {
                    val methodClassIdsString = changedMethodRefs.joinToString(" OR ") {
                        "(class_id=${dbClassNodeMap[it.owner] ?: -1} AND name='${it.name}' AND desc='${it.desc}')"
                    }
                    val sql = "SELECT ref_class_id FROM method_refs WHERE $methodClassIdsString;"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQuery(sql)
                        while (resultSet.next()) {
                            val classId = resultSet.getInt(1)
                            refClassIds.add(classId)
                        }
                    }
                }

                if (changedFieldRefs.isNotEmpty()) {
                    val fieldClassIdsString = changedFieldRefs.joinToString(" OR ") {
                        "(class_id=${dbClassNodeMap[it.owner] ?: -1} AND name='${it.name}' AND type='${it.type}')"
                    }
                    val sql2 = "SELECT ref_class_id FROM field_refs WHERE $fieldClassIdsString;"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQuery(sql2)
                        while (resultSet.next()) {
                            val classId = resultSet.getInt(1)
                            refClassIds.add(classId)
                        }
                    }
                }
            }
            if (refClassIds.isEmpty()) {
                return emptyMap()
            }

            val effectedClassNodes = mutableMapOf<String, MutableList<String>>()
            runWithTimeCost("doGetClassNodes") {
                val refClassIdsString = refClassIds.joinToString(",")
                val sql = "SELECT name, source FROM class_info WHERE id IN ($refClassIdsString);"
                connection.createStatement().use { statement ->
                    val resultSet: ResultSet = statement.executeQuery(sql)
                    while (resultSet.next()) {
                        val className = resultSet.getString(1)
                        val source = resultSet.getString(2)
                        effectedClassNodes.getOrPut(source) { mutableListOf() }.add(className)
                    }
                }
            }
            return effectedClassNodes
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

    @Synchronized
    fun recreateDatabase() {
        dbFile.delete()
        hasInit = false
        init()
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

private class DbClassNode(
    val dexFileName: String,
    val className: String,
    val classId: Int,
    val interfaceNames: List<String>,
    val superClass: String,
    val source: String?,
)