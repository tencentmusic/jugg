@file:Suppress("NOTHING_TO_INLINE")

package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceName
import com.sickworm.intellij.jugg.deploy.interfaceNameFromDesugaredDefaultMethodClass
import com.sickworm.intellij.jugg.deploy.isOfficialClassExceptAndroidX
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.math.max


class DeployDataDatabaseSqLiteHelper(private val dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    private var hasInit = false

    companion object {
        private const val VERSION = 3

        private const val ENTRY_TYPE_OTHER = 0
        private const val ENTRY_TYPE_DEX = 1
        private const val ENTRY_TYPE_RES = 2
        private const val ENTRY_TYPE_ASSETS = 3
    }

    @Synchronized
    fun init() {
        if (hasInit) {
            if (dbFile.exists()) {
                return
            } else {
                hasInit = false
            }
        }
        dbFile.parentFile?.mkdirs()
        SqLiteDriverLoader.load(logger)

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
                        recreateDatabase()
                        init()
                        return
                    }
                }
            }

            // Create a new table
            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS apk_info (
                    key TEXT NOT NULL PRIMARY KEY,
                    next_class_id INTEGER NOT NULL
                );
                
                CREATE TABLE IF NOT EXISTS entry_info (
                    name TEXT NOT NULL PRIMARY KEY,
                    checksum INTEGER NOT NULL,
                    type INTEGER NOT NULL
                );
                
                CREATE TABLE IF NOT EXISTS class_info (
                    id INTEGER NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    entry_info_name TEXT NOT NULL,
                    source TEXT,
                    super_name TEXT NOT NULL,
                    interface_names TEXT NOT NULL,
                    methods TEXT NOT NULL,
                    fields TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS class_info_name_index ON class_info(name);
                
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
                
                CREATE TABLE IF NOT EXISTS subclass_refs (
                    class_id INTEGER NOT NULL,
                    ref_class_id INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS subclass_refs_class_id_index ON subclass_refs(class_id);
                CREATE INDEX IF NOT EXISTS subclass_refs_ref_class_id_index ON subclass_refs(ref_class_id);
                
                PRAGMA schema_version = $VERSION;
            """.trimIndent()

            connection.createStatement().use { statement ->
                statement.executeUpdate(createTableSQL)
            }
        }

        hasInit = true
        logger.debug("Init database ${dbFile.name} success.")
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

            val sql = "INSERT INTO entry_info(name, checksum, type) VALUES(?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                addedDexFiles.values.forEach {
                    preparedStatement.setString(1, it.name)
                    preparedStatement.setLong(2, it.checksum)
                    preparedStatement.setInt(3, ENTRY_TYPE_DEX)
                    preparedStatement.addBatch()
                }
                addedOverlayFiles.values.forEach {
                    preparedStatement.setString(1, it.name)
                    preparedStatement.setLong(2, it.checksum)
                    if (it.isRes) {
                        preparedStatement.setInt(3, ENTRY_TYPE_RES)
                    } else if (it.isAsset) {
                        preparedStatement.setInt(3, ENTRY_TYPE_ASSETS)
                    } else {
                        preparedStatement.setInt(3, ENTRY_TYPE_OTHER)
                    }
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

            val deleteSubclassRefSql = "DELETE FROM subclass_refs WHERE class_id=? OR ref_class_id=?;"
            connection.prepareStatement(deleteSubclassRefSql).use { preparedStatement ->
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
            val sql = "INSERT INTO class_info(name, interface_names, super_name, source, entry_info_name, methods, fields, id) VALUES(?, ?, ?, ?, ?, ?, ?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.classes.values.forEach {
                    preparedStatement.setString(1, it.className)
                    preparedStatement.setString(2, it.interfaceNames.toInterfaceString())
                    preparedStatement.setString(3, it.superClass)
                    preparedStatement.setString(4, it.source)
                    preparedStatement.setString(5, it.dexFileName)
                    preparedStatement.setString(6, it.methods.toMethodString())
                    preparedStatement.setString(7, it.fields.toFieldString())
                    val classId = nextClassId++
                    preparedStatement.setInt(8, classId)
                    preparedStatement.addBatch()
                    dbClassNodeMap[it.className] = classId
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

        runWithTimeCost("doInsertSubclassRef") {
            val sql = "INSERT INTO subclass_refs(class_id, ref_class_id) VALUES(?, ?);"
            connection.prepareStatement(sql).use { preparedStatement ->
                parsedApk.subclassRefs.forEach { (className, refClassName) ->
                    val dbClassNode = dbClassNodeMap[className]
                        ?: // The class of the field is not exists in the apk. Maybe in the android.jar. Skip it.
                        return@forEach
                    refClassName.forEach {
                        preparedStatement.setInt(1, dbClassNode)
                        preparedStatement.setInt(2, dbClassNodeMap[it]!!)
                        preparedStatement.addBatch()
                    }
                }
                preparedStatement.executeBatch()
            }
        }

        runWithTimeCost("doUpdateNextClassId") {
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

            val selectEntrySQL = "SELECT name, checksum, type FROM entry_info;"
            val dbDexFiles = mutableMapOf<String, JuggFileInfo>()
            val dbOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectEntrySQL)
                while (resultSet.next()) {
                    val name = resultSet.getString("name")
                    val checksum = resultSet.getLong("checksum")
                    val type = resultSet.getInt("type")
                    if (type == ENTRY_TYPE_DEX) {
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

            val selectSQL = "SELECT name, checksum, type FROM entry_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSQL)
                while (resultSet.next()) {
                    val fileName = resultSet.getString(1)
                    val checksum = resultSet.getLong(2)
                    val type = resultSet.getInt(3)
                    if (type == ENTRY_TYPE_DEX) {
                        dexFiles[fileName] = JuggFileInfo(fileName, checksum)
                    } else {
                        overlayFiles[fileName] = JuggFileInfo(fileName, checksum)
                    }
                }
            }

            val dbClasses = mutableMapOf<Int, ClassNode>()
            val classes = mutableMapOf<String, ClassNode>()
            val selectClassSQL = "SELECT name, interface_names, super_name, source, entry_info_name, methods, fields FROM class_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val interfaceNames = resultSet.getString(2).toInterfaceList()
                    val superName = resultSet.getString(3)
                    val source = resultSet.getString(4)
                    val dexFileName = resultSet.getString(5)
                    val methodInfos = resultSet.getString(6).toMethodList(className)
                    val fieldInfos = resultSet.getString(7).toFieldList(className)
                    val id = resultSet.getInt(8)
                    val classNode = ClassNode(dexFileName, className, methodInfos, fieldInfos, interfaceNames, superName, source)
                    dbClasses[id] = classNode
                    classes[className] = classNode
                }
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

            val subclassRefs = mutableMapOf<String, MutableList<String>>()
            val selectSubclassRefSQL = "SELECT * FROM subclass_refs;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSubclassRefSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val refClassId = resultSet.getInt(2)
                    val className = dbClasses[classId]?.className ?: continue
                    val refClassName = dbClasses[refClassId]?.className ?: continue
                    subclassRefs.getOrPut(className) { mutableListOf() }.add(refClassName)
                }
            }

            return ParsedApk(apkInfo, classes, dexFiles, overlayFiles, methodRefs, fieldRefs, subclassRefs)
        }
    }

    @Synchronized
    fun getResInfos(): List<JuggFileInfo> {
        val selectSQL = "SELECT name, checksum FROM entry_info WHERE type = $ENTRY_TYPE_RES;"
        val resInfos = mutableListOf<JuggFileInfo>()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQuery(selectSQL)
                while (resultSet.next()) {
                    val resInfo = JuggFileInfo(
                        resultSet.getString(1),
                        resultSet.getLong(2),
                    )
                    resInfos.add(resInfo)
                }
            }
        }
        return resInfos
    }

    @Synchronized
    fun getClassNodes(classNames: List<String>): Map<String, ClassNode> {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val classNamesString = classNames.joinToString(",") { "'$it'" }
                val selectClassSQL = "SELECT name, interface_names, super_name, source, entry_info_name, methods, fields FROM class_info WHERE name IN ($classNamesString);"
                val classes = mutableMapOf<String, ClassNode>()
                val resultSet: ResultSet = statement.executeQuery(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val interfaceNames = resultSet.getString(2).toInterfaceList()
                    val superName = resultSet.getString(3)
                    val source = resultSet.getString(4)
                    val dexFileName = resultSet.getString(5)
                    val methodInfos = resultSet.getString(6).toMethodList(className)
                    val fieldInfos = resultSet.getString(7).toFieldList(className)
                    val classNode = ClassNode(dexFileName, className, methodInfos, fieldInfos, interfaceNames, superName, source)
                    classes[className] = classNode
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

                val sql = "SELECT name, id FROM class_info WHERE name IN ($classNamesString);"
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

            // changedMethodRefs and changedMethodRefs of subclasses
            val changedMethodRefsWithSubclasses: MutableList<MethodNodeDb> = changedMethodRefs.mapNotNull {
                val classId = dbClassNodeMap[it.owner] ?: return@mapNotNull null
                MethodNodeDb(classId, it.name, it.desc)
            }.toMutableList()

            runWithTimeCost("doGetSubClassIds") {
                var currentSuperClassIds = changedMethodRefsWithSubclasses
                    .map { it.classId }
                    .toSet()

                while (currentSuperClassIds.isNotEmpty()) {
                    val superClassIdsString = currentSuperClassIds.joinToString(",") {
                        "$it"
                    }
                    val sql = "SELECT class_id, ref_class_id FROM subclass_refs WHERE class_id IN ($superClassIdsString);"
                    val newSubclassMethodNode = mutableListOf<MethodNodeDb>()
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQuery(sql)
                        while (resultSet.next()) {
                            val classId = resultSet.getInt(1)
                            val refClassId = resultSet.getInt(2)
                            changedMethodRefsWithSubclasses
                                .filter { it.classId == classId }
                                .forEach { superClassMethodNode ->
                                    val subclassMethodNode = MethodNodeDb(refClassId, superClassMethodNode.name, superClassMethodNode.desc)
                                    newSubclassMethodNode.add(subclassMethodNode)
                                    changedMethodRefsWithSubclasses.add(subclassMethodNode)
                                    refClassIds.add(subclassMethodNode.classId)
                                }
                        }
                    }

                    val newSubclassIdsString = newSubclassMethodNode.map { it.classId }
                        .toSet().joinToString(",")
                    val sql2 = "SELECT id, name, methods FROM class_info WHERE id IN ($newSubclassIdsString);"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQuery(sql2)
                        while (resultSet.next()) {
                            val classId = resultSet.getInt(1)
                            val className = resultSet.getString(2)
                            val methods = resultSet.getString(3).toMethodList(className)

                            // filter out methods that are rewrite by subclass
                            newSubclassMethodNode.filter {
                                it.classId == classId
                            }.forEach { methodNodeDb ->
                                val isOverride = methods.any { it.name == methodNodeDb.name && it.desc == methodNodeDb.desc }
                                if (isOverride) {
                                    newSubclassMethodNode.remove(methodNodeDb)
                                }
                            }
                        }
                    }

                    currentSuperClassIds = newSubclassMethodNode.map { it.classId }.toSet()
                }
            }


            runWithTimeCost("doGetRefClassIds") {
                if (changedMethodRefsWithSubclasses.isNotEmpty()) {
                    val methodClassIdsString = changedMethodRefsWithSubclasses.joinToString(" OR ") {
                        "(class_id=${it.classId} AND name='${it.name}' AND desc='${it.desc}')"
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

                val fieldClassIds = changedFieldRefs.filter {
                    dbClassNodeMap.containsKey(it.owner)
                }
                if (fieldClassIds.isNotEmpty()) {
                    val fieldClassIdsString = fieldClassIds.joinToString(" OR ") {
                        "(class_id=${dbClassNodeMap[it.owner]!!} AND name='${it.name}' AND type='${it.type}')"
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
    fun findInterfacesWithDesugaredDefaultMethod(classNodes: List<ClassNode>): List<String> {
        val checkedClasses = mutableSetOf<String>()

        val result = mutableListOf<String>()

        var toCheckInterfaces: List<String> = classNodes
            .flatMap {
                listOf(it.superClass) + it.interfaceNames
            }.filter {
                // don't filter android X classes because they may use default method.
                // e.g. Landroidx/lifecycle/DefaultLifecycleObserver;
                !it.isOfficialClassExceptAndroidX
            }

        runWithTimeCost("doFindInterfacesWithDesugaredDefaultMethod") {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->

                    while (toCheckInterfaces.isNotEmpty()) {
                        // find interfaces with desugared default method class which has suffix of "$-CC;"
                        val defaultInterfaces = toCheckInterfaces.map { it.desugarDefaultInterfaceName }
                        val defaultInterfacesString = defaultInterfaces.joinToString(",") { "'$it'" }
                        val sql = "SELECT name FROM class_info WHERE name IN ($defaultInterfacesString);"
                        val resultSet: ResultSet = statement.executeQuery(sql)
                        while (resultSet.next()) {
                            val name = resultSet.getString(1)
                            val interfaceName = name.interfaceNameFromDesugaredDefaultMethodClass
                            result.add(interfaceName)
                        }
                        checkedClasses.addAll(toCheckInterfaces)

                        // find classes' super class and interfaces
                        val toCheckInterfacesString = toCheckInterfaces.joinToString(",") { "'$it'" }
                        val sql2 = "SELECT super_name, interface_names FROM class_info WHERE name IN ($toCheckInterfacesString);"
                        val newToCheckInterfaces = mutableSetOf<String>()
                        val resultSet2: ResultSet = statement.executeQuery(sql2)
                        while (resultSet2.next()) {
                            val superClassName = resultSet.getString(1)
                            val superInterfaceNames = resultSet.getString(2).toInterfaceList()
                            newToCheckInterfaces.add(superClassName)
                            newToCheckInterfaces.addAll(superInterfaceNames)
                        }
                        toCheckInterfaces = newToCheckInterfaces.filter {
                            !checkedClasses.contains(it) && !it.isOfficialClassExceptAndroidX
                        }
                    }
                }
            }
        }

        return result
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

    private inline fun String.toInterfaceList(): List<String> {
        return if (isEmpty()) {
            emptyList()
        } else {
            split(" ").toList()
        }
    }

    private inline fun List<String>.toInterfaceString(): String {
        return joinToString(" ")
    }


    private inline fun String.toMethodList(owner: String): List<MethodNode> {
        return if (isEmpty()) {
            emptyList()
        } else {
            split("\n").map {
                val parts = it.split(" ")
                MethodNode(owner, parts[0].toInt(), parts[1], parts[2])
            }
        }
    }

    private inline fun List<MethodNode>.toMethodString(): String {
        return joinToString("\n") {
            "${it.access} ${it.name} ${it.desc}"
        }
    }

    private inline fun String.toFieldList(owner: String): List<FieldNode> {
        return if (isEmpty()) {
            emptyList()
        } else {
            split("\n").map {
                val parts = it.split(" ")
                FieldNode(owner, parts[0].toInt(), parts[1], parts[2])
            }
        }
    }

    private inline fun List<FieldNode>.toFieldString(): String {
        return joinToString("\n") {
            "${it.access} ${it.name} ${it.type}"
        }
    }
}

private data class MethodNodeDb(
    val classId: Int,
    val name: String,
    val desc: String
)