@file:Suppress("NOTHING_TO_INLINE", "SqlNoDataSourceInspection")

package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.DexConstants
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.project.JuggException
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import kotlin.math.max
import kotlin.use


/**
 * DeployDataDatabaseSqLiteHelper provides helper utilities for deploy sq lite.
 */
class DeployDataDatabaseSqLiteHelper(val dbFile: File, private val logger: Logger) {

    private val url = "jdbc:sqlite:${dbFile.absolutePath}"

    companion object {
        private const val VERSION = 10

        private const val ENTRY_TYPE_OTHER = 0
        private const val ENTRY_TYPE_DEX = 1
        private const val ENTRY_TYPE_RES = 2
        private const val ENTRY_TYPE_ASSETS = 3

        private val File.apkFileKey get() = this.name // not use absolute path because root directory may change
    }

    @Synchronized
    fun init(isRecreate: Boolean = false) {
        dbFile.parentFile?.mkdirs()
        SqLiteDriverLoader.load(logger)

        // Create a new database connection
        DriverManager.getConnection(url).use { connection ->
            val readVersionSQL = "PRAGMA schema_version;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(readVersionSQL)
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
                CREATE TABLE IF NOT EXISTS apk_info (
                    key TEXT NOT NULL PRIMARY KEY,
                    apk_info_id INTEGER NOT NULL UNIQUE,
                    apk_name TEXT NOT NULL,
                    last_modified BIGINT NOT NULL,
                    next_class_id INTEGER NOT NULL,
                    is_enable_desugar BOOL NOT NULL
                );
                
                CREATE TABLE IF NOT EXISTS entry_info (
                    entry_info_id INTEGER NOT NULL PRIMARY KEY,
                    apk_info_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    checksum INTEGER NOT NULL,
                    type INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS entry_info_apk_index ON entry_info(apk_info_id);
                CREATE INDEX IF NOT EXISTS entry_info_name_apk_index ON entry_info(name, apk_info_id);
                
                CREATE TABLE IF NOT EXISTS class_info (
                    id INTEGER NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    entry_info_name TEXT NOT NULL,
                    entry_info_id INTEGER NOT NULL,
                    source TEXT,
                    super_name TEXT NOT NULL,
                    interface_names TEXT NOT NULL,
                    access INTEGER NOT NULL,
                    methods TEXT NOT NULL,
                    fields TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS class_info_name_index ON class_info(name);
                CREATE INDEX IF NOT EXISTS class_info_entry_id_index ON class_info(entry_info_id);
                
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

        logger.debug("Init database ${dbFile.name} success.")
    }

    @Synchronized
    fun saveParsedApkBatch(parsedApks: List<ParsedApk>, apkDiffResults: List<ParsedApkDiffResult>): List<ParsedApkUpdateResult> {
        if (parsedApks.isEmpty()) return emptyList()
        DriverManager.getConnection(url).use { connection ->
            val startTime = System.currentTimeMillis()
            connection.autoCommit = false
            try {
                val result = doInsertApkInfoBatch(connection, parsedApks, apkDiffResults)
                connection.commit()
                logger.debug("saveParsedApkBatch success. cost ${System.currentTimeMillis() - startTime}ms.")
                return result
            } catch (e: Exception) {
                connection.rollback()
                logger.error("saveParsedApkBatch failed. cost ${System.currentTimeMillis() - startTime}ms.", e)
                return apkDiffResults.map { ParsedApkUpdateResult.failed(it, e.message) }
            }
        }
    }

    private fun doInsertApkInfoBatch(connection: Connection,
                                     parsedApks: List<ParsedApk>,
                                     apkDiffResults: List<ParsedApkDiffResult>): List<ParsedApkUpdateResult> {
        logger.debug("doInsertApkInfoBatch\nparsedApks:$parsedApks\napkDiffResults: $apkDiffResults")
        val results = mutableListOf<ParsedApkUpdateResult>()
        val pairs = parsedApks.zip(apkDiffResults).filter {
            if (it.second.updatedApkInfos == 0) {
                results.add(ParsedApkUpdateResult.success(it.second))
                return@filter false
            }
            return@filter true
        }
        if (pairs.isEmpty()) return apkDiffResults.map { ParsedApkUpdateResult.success(it) }

        var nextClassId = 1
        val apkInfoIdByKey = mutableMapOf<String, Int>()
        val isEnableDesugarByApkId = mutableMapOf<Int, Boolean>()

        runWithTimeCost("doInsertApkInfo.prepare") {
            val queryAllSql = "SELECT next_class_id FROM apk_info;"
            connection.createStatement().use { st ->
                val rs = st.executeQueryAndLog(queryAllSql)
                while (rs.next()) nextClassId = max(nextClassId, rs.getInt(1))
            }

            pairs.forEach { (parsedApk, apkDiffResult) ->
                var currentApkInfoId = -1
                var oldIsEnableDesugar = true
                val selectApkSql = "SELECT apk_info_id, next_class_id, is_enable_desugar FROM apk_info WHERE key=?;"
                connection.prepareStatement(selectApkSql).use { ps ->
                    ps.setString(1, parsedApk.apkFile.apkFileKey)
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        currentApkInfoId = rs.getInt(1)
                        nextClassId = max(nextClassId, rs.getInt(2))
                        oldIsEnableDesugar = rs.getBoolean(3)
                    }
                }

                val newIsEnableDesugar = if (apkDiffResult.isFullUpdate) {
                    parsedApk.classes.keys.any { it.endsWith(desugarDefaultInterfaceSuffix) || it.endsWith(desugarDefaultInterfaceSuffix2) }
                } else oldIsEnableDesugar

                if (currentApkInfoId < 0) {
                    var nextApkInfoId = 1
                    val maxIdSql = "SELECT MAX(apk_info_id) FROM apk_info;"
                    connection.createStatement().use { st ->
                        val rs = st.executeQueryAndLog(maxIdSql)
                        if (rs.next()) nextApkInfoId = max(nextApkInfoId, rs.getInt(1) + 1)
                    }

                    val sql = "INSERT INTO apk_info(key, apk_info_id, apk_name, last_modified, next_class_id, is_enable_desugar) VALUES(?, ?, ?, ?, ?, ?);"
                    connection.prepareStatement(sql).use { ps ->
                        ps.setString(1, parsedApk.apkFile.apkFileKey)
                        ps.setInt(2, nextApkInfoId)
                        ps.setString(3, parsedApk.apkFile.name)
                        ps.setLong(4, parsedApk.apkFile.lastModified())
                        ps.setInt(5, nextClassId)
                        ps.setBoolean(6, newIsEnableDesugar)
                        ps.executeUpdate()
                    }
                    currentApkInfoId = nextApkInfoId
                } else {
                    val sql = "UPDATE apk_info SET apk_name=?, last_modified=?, next_class_id=?, is_enable_desugar=? WHERE apk_info_id=?;"
                    connection.prepareStatement(sql).use { ps ->
                        ps.setString(1, parsedApk.apkFile.name)
                        ps.setLong(2, parsedApk.apkFile.lastModified())
                        ps.setInt(3, nextClassId)
                        ps.setBoolean(4, newIsEnableDesugar)
                        ps.setInt(5, currentApkInfoId)
                        ps.executeUpdate()
                    }
                }

                apkInfoIdByKey[parsedApk.apkFile.apkFileKey] = currentApkInfoId
                isEnableDesugarByApkId[currentApkInfoId] = newIsEnableDesugar
            }

            val newApkInfoKeys = parsedApks.joinToString(",") { "\"" + it.apkFile.apkFileKey + "\"" }
            val deleteOldApkInfoSql = "DELETE FROM apk_info WHERE key NOT IN ($newApkInfoKeys);"
            connection.createStatement().use { statement ->
                @Suppress("SqlSourceToSinkFlow")
                statement.execute(deleteOldApkInfoSql)
            }
        }

        runWithTimeCost("doDeleteEntryInfo") {
            val removedDexFiles = mutableMapOf<String, JuggFileInfo>()
            val removedOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            val sql = "DELETE FROM entry_info WHERE apk_info_id=? AND name=?;"
            connection.prepareStatement(sql).use { ps ->
                pairs.forEach { (parsedApk, diff) ->
                    removedDexFiles.putAll(diff.removedDexFiles)
                    removedDexFiles.putAll(diff.updatedDexFiles)
                    removedOverlayFiles.putAll(diff.removedOverlayFiles)
                    removedOverlayFiles.putAll(diff.updatedOverlayFiles)

                    val apkId = apkInfoIdByKey[parsedApk.apkFile.apkFileKey]!!
                    removedDexFiles.values.forEach {
                        ps.setInt(1, apkId)
                        ps.setString(2, it.name)
                        ps.addBatch()
                    }
                    removedOverlayFiles.values.forEach {
                        ps.setInt(1, apkId)
                        ps.setString(2, it.name)
                        ps.addBatch()
                    }
                }
                ps.executeBatch()
            }
        }

        val entryInfoIdByNameByApkId = mutableMapOf<Int, MutableMap<String, Int>>()

        runWithTimeCost("doInsertEntryInfo") {
            var nextEntryInfoId = 1
            val maxEntrySql = "SELECT MAX(entry_info_id) FROM entry_info;"
            connection.createStatement().use { st ->
                val rs = st.executeQueryAndLog(maxEntrySql)
                if (rs.next()) nextEntryInfoId = max(nextEntryInfoId, rs.getInt(1) + 1)
            }

            val sql = "INSERT INTO entry_info(entry_info_id, apk_info_id, name, checksum, type) VALUES(?, ?, ?, ?, ?);"
            connection.prepareStatement(sql).use { ps ->
                pairs.forEach { (parsedApk, diff) ->
                    val apkId = apkInfoIdByKey[parsedApk.apkFile.apkFileKey]!!
                    val addedDexFiles = diff.addedDexFiles + diff.updatedDexFiles
                    val addedOverlayFiles = diff.addedOverlayFiles + diff.updatedOverlayFiles
                    addedDexFiles.values.forEach {
                        ps.setInt(1, nextEntryInfoId++)
                        ps.setInt(2, apkId)
                        ps.setString(3, it.name)
                        ps.setLong(4, it.checksum)
                        ps.setInt(5, ENTRY_TYPE_DEX)
                        ps.addBatch()
                    }
                    addedOverlayFiles.values.forEach {
                        ps.setInt(1, nextEntryInfoId++)
                        ps.setInt(2, apkId)
                        ps.setString(3, it.name)
                        ps.setLong(4, it.checksum)
                        ps.setInt(5, if (it.isRes) ENTRY_TYPE_RES else if (it.isAsset) ENTRY_TYPE_ASSETS else ENTRY_TYPE_OTHER)
                        ps.addBatch()
                    }
                }
                ps.executeBatch()
            }
        }

        runWithTimeCost("doLoadEntryIds") {
            val sql = "SELECT name, entry_info_id, apk_info_id FROM entry_info;"
            connection.createStatement().use { st ->
                val rs = st.executeQueryAndLog(sql)
                while (rs.next()) {
                    val name = rs.getString(1)
                    val entryId = rs.getInt(2)
                    val apkId = rs.getInt(3)
                    val map = entryInfoIdByNameByApkId.getOrPut(apkId) { mutableMapOf() }
                    map[name] = entryId
                }
            }
        }

        val dbClassNodeMap = mutableMapOf<String, Int>()
        val removedClasses = mutableMapOf<String, Int>()
        val updatedClasses = mutableMapOf<String, Int>()

        runWithTimeCost("doGetClassInfo") {
            val removedDexNames = mutableSetOf<String>()
            val updatedDexNames = mutableSetOf<String>()
            val refClassNames = mutableSetOf<String>()
            pairs.forEach { (parsedApk, diff) ->
                removedDexNames.addAll(diff.removedDexFiles.keys)
                updatedDexNames.addAll(diff.updatedDexFiles.keys)
                parsedApk.methodRefs.keys.forEach { refClassNames.add(it.owner) }
                parsedApk.fieldRefs.keys.forEach { refClassNames.add(it.owner) }
                parsedApk.subclassRefs.keys.forEach { refClassNames.add(it) }
            }
            val totalWhereCount = removedDexNames.size + updatedDexNames.size + refClassNames.size
            if (totalWhereCount == 0) {
                return@runWithTimeCost
            }

            val selectClassSQL = if (totalWhereCount > 10000) {
                // query performance optimize
                "SELECT name, entry_info_name, id FROM class_info;"
            } else {
                val conditions = mutableListOf<String>()
                if (removedDexNames.isNotEmpty()) {
                    val s = removedDexNames.joinToString(",") { "'$it'" }
                    conditions.add("entry_info_name IN ($s)")
                }
                if (updatedDexNames.isNotEmpty()) {
                    val s = updatedDexNames.joinToString(",") { "'$it'" }
                    conditions.add("entry_info_name IN ($s)")
                }
                if (refClassNames.isNotEmpty()) {
                    val s = refClassNames.joinToString(",") { "'$it'" }
                    conditions.add("name IN ($s)")
                }
                "SELECT name, entry_info_name, id FROM class_info WHERE ${conditions.joinToString(" OR ")};"
            }
            connection.createStatement().use { st ->
                val rs = st.executeQueryAndLog(selectClassSQL)
                while (rs.next()) {
                    val className = rs.getString(1)
                    val entryName = rs.getString(2)
                    val id = rs.getInt(3)
                    dbClassNodeMap[className] = id
                    if (removedDexNames.contains(entryName)) removedClasses[className] = id
                    if (updatedDexNames.contains(entryName)) updatedClasses[className] = id
                }
            }
        }

        runWithTimeCost("doDeleteAllClassInfo") {
            val dbDeleteClasses = removedClasses + updatedClasses
            if (dbDeleteClasses.isNotEmpty()) {
                val deleteClassSql = "DELETE FROM class_info WHERE id=?;"
                connection.prepareStatement(deleteClassSql).use { ps ->
                    updatedClasses.values.forEach { ps.setInt(1, it); ps.addBatch() }
                    ps.executeBatch()
                }
                val deleteMethodRefSql = "DELETE FROM method_refs WHERE ref_class_id=?;"
                connection.prepareStatement(deleteMethodRefSql).use { ps ->
                    dbDeleteClasses.values.forEach { ps.setInt(1, it); ps.addBatch() }
                    ps.executeBatch()
                }
                val deleteFieldRefSql = "DELETE FROM field_refs WHERE ref_class_id=?;"
                connection.prepareStatement(deleteFieldRefSql).use { ps ->
                    dbDeleteClasses.values.forEach { ps.setInt(1, it); ps.addBatch() }
                    ps.executeBatch()
                }
                val deleteSubclassRefSql = "DELETE FROM subclass_refs WHERE ref_class_id=?;"
                connection.prepareStatement(deleteSubclassRefSql).use { ps ->
                    dbDeleteClasses.values.forEach { ps.setInt(1, it); ps.addBatch() }
                    ps.executeBatch()
                }
            }
        }

        runWithTimeCost("doInsertClassInfo") {
            val sql = "INSERT INTO class_info(name, interface_names, super_name, source, entry_info_name, entry_info_id, access, methods, fields, id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?);"
            connection.prepareStatement(sql).use { ps ->
                pairs.forEach { (parsedApk, _) ->
                    val apkId = apkInfoIdByKey[parsedApk.apkFile.apkFileKey]!!
                    val idMap = entryInfoIdByNameByApkId[apkId] ?: mutableMapOf()
                    parsedApk.classes.values.forEach { cn ->
                        ps.setString(1, cn.className)
                        ps.setString(2, cn.interfaceNames.toInterfaceString())
                        ps.setString(3, cn.superClass)
                        ps.setString(4, cn.source)
                        ps.setString(5, cn.dexFileName)
                        ps.setInt(6, idMap[cn.dexFileName] ?: 0)
                        ps.setInt(7, cn.access)
                        ps.setString(8, cn.methods.toMethodString())
                        ps.setString(9, cn.fields.toFieldString())
                        val classId = updatedClasses[cn.className] ?: nextClassId++
                        ps.setInt(10, classId)
                        ps.addBatch()
                        dbClassNodeMap[cn.className] = classId
                    }
                }
                ps.executeBatch()
            }
        }

        runWithTimeCost("doInsertMethodRef") {
            val sql = "INSERT INTO method_refs(class_id, name, desc, ref_class_id) VALUES(?, ?, ?, ?);"
            connection.prepareStatement(sql).use { ps ->
                pairs.forEach { (parsedApk, _) ->
                    parsedApk.methodRefs.forEach { (methodNode, refClasses) ->
                        val dbClassNode = dbClassNodeMap[methodNode.owner] ?: return@forEach
                        refClasses.forEach {
                            val refId = dbClassNodeMap[it] ?: return@forEach
                            ps.setInt(1, dbClassNode)
                            ps.setString(2, methodNode.name)
                            ps.setString(3, methodNode.desc)
                            ps.setInt(4, refId)
                            ps.addBatch()
                        }
                    }
                }
                ps.executeBatch()
            }
        }

        runWithTimeCost("doInsertFieldRef") {
            val sql = "INSERT INTO field_refs(class_id, name, type, ref_class_id) VALUES(?, ?, ?, ?);"
            connection.prepareStatement(sql).use { ps ->
                pairs.forEach { (parsedApk, _) ->
                    parsedApk.fieldRefs.forEach { (fieldNode, refClassIds) ->
                        val dbClassNode = dbClassNodeMap[fieldNode.owner] ?: return@forEach
                        refClassIds.forEach {
                            val refId = dbClassNodeMap[it] ?: return@forEach
                            ps.setInt(1, dbClassNode)
                            ps.setString(2, fieldNode.name)
                            ps.setString(3, fieldNode.type)
                            ps.setInt(4, refId)
                            ps.addBatch()
                        }
                    }
                }
                ps.executeBatch()
            }
        }

        runWithTimeCost("doInsertSubclassRef") {
            val sql = "INSERT INTO subclass_refs(class_id, ref_class_id) VALUES(?, ?);"
            connection.prepareStatement(sql).use { ps ->
                pairs.forEach { (parsedApk, _) ->
                    parsedApk.subclassRefs.forEach { (className, refClassName) ->
                        val dbClassNode = dbClassNodeMap[className] ?: return@forEach
                        refClassName.forEach {
                            val refId = dbClassNodeMap[it] ?: return@forEach
                            ps.setInt(1, dbClassNode)
                            ps.setInt(2, refId)
                            ps.addBatch()
                        }
                    }
                }
                ps.executeBatch()
            }
        }

        runWithTimeCost("doUpdateNextClassId") {
            val sql = "UPDATE apk_info SET next_class_id=? WHERE apk_info_id=?;"
            connection.prepareStatement(sql).use { ps ->
                isEnableDesugarByApkId.keys.forEach { apkId ->
                    ps.setInt(1, nextClassId)
                    ps.setInt(2, apkId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        pairs.forEach { (parsedApk, diff) ->
            val added = parsedApk.classes.keys.filter { !updatedClasses.containsKey(it) }
            results.add(ParsedApkUpdateResult.success(diff).copy(
                addedClasses = added,
                removedClasses = removedClasses.keys.toList(),
                updatedClasses = updatedClasses.keys.toList(),
            ))
        }
        return results
    }

    @Synchronized
    fun diffApk(apkEntries: ApkEntries): ParsedApkDiffResult {
        logger.debug("diffApk apkEntries: dexFiles ${apkEntries.dexFiles.size}")
        DriverManager.getConnection(url).use { connection ->
            // try find existing apk_info row
            var apkInfoId: Int? = null
            var lastModified: Long = 0
            val selectApkSQL = "SELECT apk_info_id, last_modified FROM apk_info WHERE key=?;"
            connection.prepareStatement(selectApkSQL).use { ps ->
                ps.setString(1, apkEntries.apkFile.apkFileKey)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    apkInfoId = rs.getInt(1)
                    lastModified = rs.getLong(2)
                }
            }

            if (apkInfoId == null) {
                // no apk info
                return ParsedApkDiffResult(
                    apkEntries.apkFile,
                    updatedApkInfos = 1,
                    addedOverlayFiles = apkEntries.overlayFiles,
                    addedDexFiles = apkEntries.dexFiles,
                    isFullUpdate = true,
                )
            }
            if (lastModified == apkEntries.apkFile.lastModified()) {
                // apk not changed
                return ParsedApkDiffResult(apkEntries.apkFile, updatedApkInfos = 0)
            }

            val selectEntrySQL = "SELECT name, checksum, type FROM entry_info WHERE apk_info_id=$apkInfoId;"
            val dbDexFiles = mutableMapOf<String, JuggFileInfo>()
            val dbOverlayFiles = mutableMapOf<String, JuggFileInfo>()
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(selectEntrySQL)
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
                apkEntries.apkFile,
                updatedApkInfos = 1,
                addedOverlayFiles = addedOverlayFiles,
                removedOverlayFiles = removedOverlayFiles,
                updatedOverlayFiles = updatedOverlayFiles,
                addedDexFiles = addedDexFiles,
                removedDexFiles = removedDexFiles,
                updatedDexFiles = updatedDexFiles,
                isFullUpdate = dbDexFiles.isEmpty(),
            )
        }
    }

    @TestOnly
    @Synchronized
    fun getParsedApk(apkFile: File): ParsedApk? {
        logger.debug("getParsedApk $apkFile")

        var currentApkInfoId = -1
        DriverManager.getConnection(url).use { connection0 ->
            val sql = "SELECT apk_info_id FROM apk_info WHERE key=? AND last_modified=?;"
            connection0.prepareStatement(sql).use { ps ->
                ps.setString(1, apkFile.apkFileKey)
                ps.setLong(2, apkFile.lastModified())
                val rs = ps.executeQuery()
                if (rs.next()) {
                    currentApkInfoId = rs.getInt(1)
                }
            }
        }
        if (currentApkInfoId < 0) {
            logger.warn("Apk info key not found: ${apkFile.apkFileKey}")
            return null
        }

        DriverManager.getConnection(url).use { connection ->
            val dexFiles = mutableMapOf<String, JuggFileInfo>()
            val overlayFiles = mutableMapOf<String, JuggFileInfo>()

            val selectSQL = "SELECT name, checksum, type FROM entry_info WHERE apk_info_id=$currentApkInfoId;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(selectSQL)
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
            val selectClassSQL = "SELECT name, interface_names, super_name, source, entry_info_name, access, methods, fields, id FROM class_info;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(selectClassSQL)
                while (resultSet.next()) {
                    val className = resultSet.getString(1)
                    val interfaceNames = resultSet.getString(2).toInterfaceList()
                    val superName = resultSet.getString(3)
                    val source = resultSet.getString(4)
                    val dexFileName = resultSet.getString(5)
                    val access = resultSet.getInt(6)
                    val methodInfos = resultSet.getString(7).toMethodList(className)
                    val fieldInfos = resultSet.getString(8).toFieldList(className)
                    val id = resultSet.getInt(9)
                    val classNode = ClassNode(dexFileName, className, access, methodInfos, fieldInfos, interfaceNames, superName, source)
                    dbClasses[id] = classNode
                    classes[className] = classNode
                }
            }

            val methodRefs = mutableMapOf<MethodNode, MutableList<String>>()
            val selectMethodRefSQL = "SELECT * FROM method_refs;"
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(selectMethodRefSQL)
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
                val resultSet: ResultSet = statement.executeQueryAndLog(selectFieldRefSQL)
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
                val resultSet: ResultSet = statement.executeQueryAndLog(selectSubclassRefSQL)
                while (resultSet.next()) {
                    val classId = resultSet.getInt(1)
                    val refClassId = resultSet.getInt(2)
                    val className = dbClasses[classId]?.className ?: continue
                    val refClassName = dbClasses[refClassId]?.className ?: continue
                    subclassRefs.getOrPut(className) { mutableListOf() }.add(refClassName)
                }
            }

            return ParsedApk(apkFile, classes, dexFiles, overlayFiles, methodRefs, fieldRefs, subclassRefs)
        }
    }

    @Synchronized
    fun getResInfos(apkFile: File, isNeedRes: Boolean, isNeedAsset: Boolean): List<JuggFileInfo> {
        logger.debug("getResInfos")

        if (!isNeedRes && !isNeedAsset) return emptyList()

        val apkFileKey = apkFile.apkFileKey
        val queryApkInfoIdSQL = "SELECT apk_info_id FROM apk_info WHERE key=?;"
        var apkInfoId: Int = -1
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement(queryApkInfoIdSQL).use { ps ->
                ps.setString(1, apkFileKey)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    apkInfoId = rs.getInt(1)
                }
            }
        }
        if (apkInfoId == -1) {
            logger.warn("Apk info key not found: $apkFileKey")
            throw JuggException.apkDbNotFound(apkFileKey)
        }

        var whereCondition = ""
        if (isNeedRes) {
            whereCondition = "type = $ENTRY_TYPE_RES"
        }
        if (isNeedAsset) {
            if (whereCondition.isNotEmpty()) whereCondition += " or "
            whereCondition += "type = $ENTRY_TYPE_ASSETS or type = $ENTRY_TYPE_OTHER"
        }
        whereCondition = "apk_info_id = $apkInfoId and ($whereCondition)"

        val selectSQL = "SELECT name, checksum FROM entry_info WHERE $whereCondition;"
        val resInfos = mutableListOf<JuggFileInfo>()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(selectSQL)
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
        logger.debug("getClassNodes ${classNames.size}")

        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val classes = mutableMapOf<String, ClassNode>()

                // avoid Exception: [SQLITE_TOOBIG] String or BLOB exceeds size limit (statement too long)
                classNames.chunked(2000).forEach { subClassNames ->
                    val classNamesString = subClassNames.joinToString(",") { "'$it'" }
                    val selectClassSQL = "SELECT name, interface_names, super_name, source, entry_info_name, access, methods, fields FROM class_info WHERE name IN ($classNamesString);"
                    val resultSet: ResultSet = statement.executeQueryAndLog(selectClassSQL)
                    while (resultSet.next()) {
                        val className = resultSet.getString(1)
                        val interfaceNames = resultSet.getString(2).toInterfaceList()
                        val superName = resultSet.getString(3)
                        val source = resultSet.getString(4)
                        val dexFileName = resultSet.getString(5)
                        val access = resultSet.getInt(6)
                        val methodInfos = resultSet.getString(7).toMethodList(className)
                        val fieldInfos = resultSet.getString(8).toFieldList(className)
                        val classNode = ClassNode(dexFileName, className, access, methodInfos, fieldInfos, interfaceNames, superName, source)
                        classes[className] = classNode
                    }
                }

                return classes
            }
        }
    }

    @Synchronized
    fun getEffectedClassNodes(
        changedMethodRefs: List<MethodNode>,
        changedFieldRefs: List<FieldNode>,
        changedAbstractClasses: List<ClassNode>,
    ): List<EffectedClassNode> {
        logger.debug("getEffectedClassNodes changedMethodRefs $changedMethodRefs")
        logger.debug("getEffectedClassNodes changedFieldRefs $changedFieldRefs $changedAbstractClasses")
        logger.debug("getEffectedClassNodes changedAbstractClasses $changedAbstractClasses")

        DriverManager.getConnection(url).use { connection ->
            // step 1. build dbClassNodeMap to get classId
            val dbClassNodeMap = mutableMapOf<String, Int>() // className -> classId map
            runWithTimeCost("doGetClassIds") {
                val classNameList = mutableSetOf<String>()
                changedMethodRefs.forEach { classNameList.add(it.owner) }
                changedFieldRefs.forEach { classNameList.add(it.owner) }
                changedAbstractClasses.forEach { classNameList.add(it.className) }
                val classNamesString = classNameList.joinToString(",") { "'$it'" }

                val sql = "SELECT name, id FROM class_info WHERE name IN ($classNamesString);"
                connection.createStatement().use { statement ->
                    val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                    while (resultSet.next()) {
                        val className = resultSet.getString(1)
                        val classId = resultSet.getInt(2)
                        dbClassNodeMap[className] = classId
                    }
                }
            }
            val refClassIds = mutableMapOf<Int, MutableList<Int>>() // Map<effected class ids, List<effect by class ids>>


            // step 2. get all subclasses of [changedMethodRefs] to supports invoke-virtual.
            // Note: changedMethodRefsWithSubclasses is also used by step 3, so ALL methods (including static) must be kept.
            // Static methods have no subclass dispatch semantics, so only exclude them from the initial subclass traversal set.
            val changedMethodRefsWithSubclasses: MutableList<MethodNodeDb> = changedMethodRefs.mapNotNull {
                val classId = dbClassNodeMap[it.owner] ?: return@mapNotNull null
                MethodNodeDb(classId, it.name, it.desc)
            }.toMutableList() // classes and subclasses of [changedMethodRefs]
            runWithTimeCost("doGetSubClassIds") {
                var currentSuperClassIds = changedMethodRefs
                    .filter { it.access == MethodNode.MISS_ACCESS || (it.access and DexConstants.ACC_STATIC) == 0 }
                    .mapNotNull { dbClassNodeMap[it.owner] }
                    .toSet()

                while (currentSuperClassIds.isNotEmpty()) {
                    val superClassIdsString = currentSuperClassIds.joinToString(",") {
                        "$it"
                    }
                    val sql = "SELECT class_id, ref_class_id FROM subclass_refs WHERE class_id IN ($superClassIdsString);"
                    val newSubclassMethodNode = mutableListOf<MethodNodeDb>()
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                        while (resultSet.next()) {
                            val classId = resultSet.getInt(1)
                            val refClassId = resultSet.getInt(2)
                            changedMethodRefsWithSubclasses
                                .filter { it.classId == classId }
                                .forEach { superClassMethodNode ->
                                    val subclassMethodNode = MethodNodeDb(refClassId, superClassMethodNode.name, superClassMethodNode.desc)
                                    newSubclassMethodNode.add(subclassMethodNode)
                                    changedMethodRefsWithSubclasses.add(subclassMethodNode)
                                    refClassIds.getOrPut(subclassMethodNode.classId) { mutableListOf() }.add(classId)
                                }
                        }
                    }

                    if (newSubclassMethodNode.isEmpty()) {
                        break
                    }
                    val newSubclassIdsString = newSubclassMethodNode.map { it.classId }
                        .toSet().joinToString(",")
                    val sql2 = "SELECT id, name, methods FROM class_info WHERE id IN ($newSubclassIdsString);"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQueryAndLog(sql2)
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

            // step 3. get all ref class ids of [changedMethodRefsWithSubclasses] and [changedFieldRefs]
            runWithTimeCost("doGetRefClassIds") {
                // get class ids by method refs
                if (changedMethodRefsWithSubclasses.isNotEmpty()) {
                    val methodClassIdsStringList = changedMethodRefsWithSubclasses.map {
                        "(class_id=${it.classId} AND name='${it.name}' AND desc='${it.desc}')"
                    }
                    // avoid Exception: [SQLITE_ERROR] SQL error or missing database (Expression tree is too large (maximum depth 1000))
                    methodClassIdsStringList.chunked(900).forEach {
                        val methodClassIdsString = it.joinToString(" OR ")
                        val sql = "SELECT class_id, ref_class_id FROM method_refs WHERE $methodClassIdsString;"
                        connection.createStatement().use { statement ->
                            val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                            while (resultSet.next()) {
                                val classId = resultSet.getInt(1)
                                val refClassId = resultSet.getInt(2)
                                refClassIds.getOrPut(refClassId) { mutableListOf() }.add(classId)
                            }
                        }
                    }
                }

                // get class ids by field refs
                val fieldClassIds = changedFieldRefs.filter {
                    dbClassNodeMap.containsKey(it.owner)
                }
                if (fieldClassIds.isNotEmpty()) {
                    // avoid Exception: [SQLITE_ERROR] SQL error or missing database (Expression tree is too large (maximum depth 1000))
                    fieldClassIds.chunked(900).forEach { fieldNode ->
                        val fieldClassIdsString = fieldNode.joinToString(" OR ") {
                            "(class_id=${dbClassNodeMap[it.owner]!!} AND name='${it.name}' AND type='${it.type}')"
                        }
                        val sql2 = "SELECT class_id, ref_class_id FROM field_refs WHERE $fieldClassIdsString;"
                        connection.createStatement().use { statement ->
                            val resultSet: ResultSet = statement.executeQueryAndLog(sql2)
                            while (resultSet.next()) {
                                val classId = resultSet.getInt(1)
                                val refClassId = resultSet.getInt(2)
                                refClassIds.getOrPut(refClassId) { mutableListOf() }.add(classId)
                            }
                        }
                    }
                }
            }

            // step 4. get all non-abstract subclasses of [changedAbstractClasses]
            runWithTimeCost("doGetAbstractSubClassIds") {
                var currentSuperClassIds: List<Int> = changedAbstractClasses.mapNotNull { dbClassNodeMap[it.className] }
                while (currentSuperClassIds.isNotEmpty()) {
                    val superClassIdsString = currentSuperClassIds.joinToString(",") {
                        "$it"
                    }
                    val getSubclassesSql = "SELECT class_id, ref_class_id FROM subclass_refs WHERE class_id IN ($superClassIdsString);"
                    val newSubclassIds = mutableMapOf<Int, MutableList<Int>>()
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQueryAndLog(getSubclassesSql)
                        while (resultSet.next()) {
                            val classId = resultSet.getInt(1)
                            val refClassId = resultSet.getInt(2)
                            newSubclassIds.getOrPut(refClassId) { mutableListOf() }.add(classId)
                        }
                    }

                    val newSubclassIdsString = newSubclassIds.keys.joinToString(",")
                    val getAccessClassIds = "SELECT id, access FROM class_info WHERE id IN ($newSubclassIdsString);"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQueryAndLog(getAccessClassIds)
                        while (resultSet.next()) {
                            val id = resultSet.getInt(1)
                            val access = resultSet.getInt(2)
                            // abstract class is also effected if changedAbstractClass is an interface with default methods
                            refClassIds.getOrPut(id) { mutableListOf() }.addAll(newSubclassIds[id]!!)
                            val isAbstract = access and DexConstants.ACC_ABSTRACT != 0
                            if (!isAbstract) {
                                newSubclassIds.remove(id)
                            }
                        }
                    }

                    currentSuperClassIds = newSubclassIds.keys.toList()
                }
            }

            // step 5. get all effected class nodes by [refClassIds]
            val effectedClassNodes = mutableListOf<EffectedClassNode>()
            if (refClassIds.isNotEmpty()) {
                runWithTimeCost("doGetClassNodes") {
                    val allClassIds = mutableSetOf<Int>()
                    refClassIds.forEach { (effectClassId, effectByClassIds) ->
                        allClassIds.add(effectClassId)
                        allClassIds.addAll(effectByClassIds)
                    }
                    val allClassIdsString = allClassIds.joinToString(",")

                    val idNameMap = mutableMapOf<Int, String>()
                    val idSourceMap = mutableMapOf<Int, String>()
                    val sql = "SELECT id, name, source FROM class_info WHERE id IN ($allClassIdsString);"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                        while (resultSet.next()) {
                            val id = resultSet.getInt(1)
                            val className = resultSet.getString(2)
                            val source = resultSet.getString(3)

                            if (refClassIds.containsKey(id)) {
                                idSourceMap[id] = source
                            }
                            idNameMap[id] = className
                        }
                    }

                    refClassIds.forEach { (effectClassId, effectByClassIds) ->
                        val className = idNameMap[effectClassId]!!
                        val classSource = idSourceMap[effectClassId]!!
                        val effectedByClasses = effectByClassIds.map {
                            idNameMap[it]!!
                        }
                        effectedClassNodes.add(
                            EffectedClassNode(
                                className,
                                classSource,
                                effectedByClasses,
                                EffectedClassNode.EffectedType.SOURCE,
                            )
                        )
                    }
                }
            }

            logger.debug("getEffectedClassNodes result $effectedClassNodes")
            return effectedClassNodes
        }
    }

    @Synchronized
    fun getEffectedClassNodesForMinify(
        maybeMinifiedRemoveClasses: ParsedDex?,
        deployedClasses: Set<String>,
    ): List<EffectedClassNode> {
        if (maybeMinifiedRemoveClasses == null) {
            return emptyList()
        }

        val dexedClasses = deployedClasses.toMutableSet()
        dexedClasses += maybeMinifiedRemoveClasses.classDeployItems
            .flatMap { it.classNodes }
            .map { it.className }
            .toSet()
        logger.debug("checkMaybeMinifiedRemoveClass: checking ${maybeMinifiedRemoveClasses.classDeployItems.size} deploy items")

        val result = mutableListOf<EffectedClassNode>()

        DriverManager.getConnection(url).use { connection ->
            runWithTimeCost("checkMaybeMinifiedRemoveClass") {
                // Collect all class names from maybeMinifiedRemoveClasses
                val suspectClassNames = mutableSetOf<String>()

                // 1. Collect from methodRefs.key.owner
                maybeMinifiedRemoveClasses.methodRefs.keys.forEach { methodNode ->
                    suspectClassNames.add(methodNode.owner)
                }
                // 2. Collect from fieldRefs.key.owner
                maybeMinifiedRemoveClasses.fieldRefs.keys.forEach { fieldNode ->
                    suspectClassNames.add(fieldNode.owner)
                }
                // 3. Collect from subclassRefs.key
                suspectClassNames.addAll(maybeMinifiedRemoveClasses.subclassRefs.keys)
                // 4. filter out classes in dexedClasses (no need check again)
                suspectClassNames.removeAll(dexedClasses)
                if (suspectClassNames.isEmpty()) {
                    logger.debug("checkMaybeMinifiedRemoveClass: no suspect classes found")
                    return@runWithTimeCost
                }
                logger.debug("checkMaybeMinifiedRemoveClass: checking ${suspectClassNames.size} suspect classes")

                // Query database to find which classes exist and their methods/fields/hierarchy
                val existingClasses = mutableSetOf<String>()
                val dbClassInfoMap = mutableMapOf<String, ClassNode>() // Map<className, uncompleted ClassNode>

                // Split into chunks to avoid SQL query too long
                suspectClassNames.chunked(2000).forEach { chunk ->
                    val classNamesString = chunk.joinToString(",") { "'$it'" }
                    val sql = "SELECT name, methods, fields, source, super_name, interface_names FROM class_info WHERE name IN ($classNamesString);"
                    connection.createStatement().use { statement ->
                        val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                        while (resultSet.next()) {
                            val className = resultSet.getString(1)
                            val methodsStr = resultSet.getString(2)
                            val fieldsStr = resultSet.getString(3)
                            val source = resultSet.getString(4)
                            val superName = resultSet.getString(5)
                            val interfaceNames = resultSet.getString(6).toInterfaceList()
                            existingClasses.add(className)

                            val methods = methodsStr.toMethodList(className)
                            val fields = fieldsStr.toFieldList(className)
                            dbClassInfoMap[className] = ClassNode(
                                "", className, 0, methods, fields, interfaceNames, superName, source
                            )
                        }
                    }
                }

                // Check 1: Find completely removed classes
                val removedClasses = suspectClassNames - existingClasses
                logger.debug("checkMaybeMinifiedRemoveClass: found $removedClasses completely removed classes")

                // Create EffectedClassNode for each completely removed class
                // Find which classes reference the removed class from maybeMinifiedRemoveClasses
                removedClasses.forEach { className ->
                    val referencedBy = mutableSetOf<String>()

                    // Check methodRefs for methods of this removed class
                    maybeMinifiedRemoveClasses.methodRefs.forEach { (methodNode, refClassNames) ->
                        if (methodNode.owner == className) {
                            referencedBy.addAll(refClassNames)
                        }
                    }

                    // Check fieldRefs for fields of this removed class
                    maybeMinifiedRemoveClasses.fieldRefs.forEach { (fieldNode, refClassNames) ->
                        if (fieldNode.owner == className) {
                            referencedBy.addAll(refClassNames)
                        }
                    }

                    // Check subclassRefs if this removed class is a superclass
                    maybeMinifiedRemoveClasses.subclassRefs[className]?.let { subclasses ->
                        referencedBy.addAll(subclasses)
                    }

                    result.add(EffectedClassNode(
                        className = className,
                        sourceFileName = EffectedClassNode.SOURCE_NOT_FOUND, // source file not found, need to found in .class classpath
                        effectedByClasses = referencedBy.toList(),
                        effectedType = EffectedClassNode.EffectedType.SOURCE
                    ))
                }

                // Check 2: Find classes with removed methods or fields
                val deployMethodsMap = mutableMapOf<String, MutableList<MethodNode>>()
                val deployFieldsMap = mutableMapOf<String, MutableList<FieldNode>>()
                maybeMinifiedRemoveClasses.methodRefs.forEach { (methodNode, _) ->
                    deployMethodsMap.getOrPut(methodNode.owner) { mutableListOf() }.add(methodNode)
                }
                maybeMinifiedRemoveClasses.fieldRefs.forEach { (fieldNode, _) ->
                    deployFieldsMap.getOrPut(fieldNode.owner) { mutableListOf() }.add(fieldNode)
                }
                existingClasses.forEach { className ->
                    val classNode = dbClassInfoMap[className] ?: return@forEach

                    // Check for removed methods
                    val dbMethods = classNode.methods
                    val deployMethods = deployMethodsMap[className] ?: emptyList()
                    val removedMethods = deployMethods.filter { methodNode ->
                        dbMethods.none { it.equalsWithoutAccess(methodNode) } && !isMethodInHierarchy(
                            className = className,
                            target = methodNode,
                            classInfoMap = dbClassInfoMap,
                        )
                    }

                    // Check for removed fields
                    val deployFields = deployFieldsMap[className] ?: emptyList()
                    val dbFields = classNode.fields
                    val removedFields = deployFields.filter { fieldNode ->
                        dbFields.none { it.equalsWithoutAccess(fieldNode) } && !isFieldInHierarchy(
                            className = className,
                            target = fieldNode,
                            classInfoMap = dbClassInfoMap,
                        )
                    }

                    if (removedMethods.isNotEmpty() || removedFields.isNotEmpty()) {
                        val referencedBy = mutableSetOf<String>()
                        // Find classes that reference the removed methods from maybeMinifiedRemoveClasses
                        removedMethods.forEach { methodNode ->
                            maybeMinifiedRemoveClasses.methodRefs[methodNode]?.let { refs ->
                                referencedBy.addAll(refs)
                            }
                        }
                        // Find classes that reference the removed fields from maybeMinifiedRemoveClasses
                        removedFields.forEach { fieldNode ->
                            maybeMinifiedRemoveClasses.fieldRefs[fieldNode]?.let { refs ->
                                referencedBy.addAll(refs)
                            }
                        }

                        result.add(EffectedClassNode(
                            className = className,
                            sourceFileName = classNode.source,
                            effectedByClasses = referencedBy.toList(),
                            effectedType = EffectedClassNode.EffectedType.SOURCE,
                        ))

                        logger.debug("checkMaybeMinifiedRemoveClass: class $className has removed members - methods: $removedMethods, fields: $removedFields, referenced by: $referencedBy")
                    }
                }

                logger.debug("checkMaybeMinifiedRemoveClass: returning ${result.size} effected class nodes")
            }
        }

        return result
    }

    /**
     * Get all interfaces of a class
     * @return Map<Interfaces, Parent>
     */
    @Synchronized
    fun getAllInterfacesOfClass(interfaces: List<String>, staticInvocations: List<String>, incDeployNodes: Map<String, ClassNode>): Map<String, String?> {
        logger.debug("getAllDefaultInterfacesOfClass interfaces $interfaces, staticInvocations $staticInvocations")

        // TODO we need to filter interface in APK if supports multiple APKs
        val result: MutableMap<String, String?> = interfaces.associateWith { null }.toMutableMap()

        val checkedClasses = mutableSetOf<String>()
        var toCheckInterfaces = interfaces

        DriverManager.getConnection(url).use { connection ->
            runWithTimeCost("getAllInterfacesOfClass") {
                connection.createStatement().use { statement ->
                    while (toCheckInterfaces.isNotEmpty()) {
                        val newToCheckInterfaces = mutableSetOf<String>()

                        // search in IncDeployNodes first
                        val dbCheckInterfaces = mutableListOf<String>()
                        toCheckInterfaces.forEach {
                            if (incDeployNodes.containsKey(it)) {
                                logger.debug("getAllDefaultInterfacesOfClass found in incDeployNodes $it")
                                incDeployNodes[it]!!.interfaceNames.forEach { interfaceName ->
                                    result[interfaceName] = it
                                }
                                newToCheckInterfaces.addAll(incDeployNodes[it]!!.interfaceNames)
                            } else {
                                dbCheckInterfaces.add(it)
                            }
                        }

                        val toCheckInterfacesString = dbCheckInterfaces.joinToString(",") { "'$it'" }
                        val sql = "SELECT name, interface_names FROM class_info WHERE name IN ($toCheckInterfacesString);"
                        val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                        while (resultSet.next()) {
                            val className = resultSet.getString(1)
                            val interfaceNames = resultSet.getString(2).toInterfaceList()
                            interfaceNames.forEach {
                                result[it] = className
                            }
                            newToCheckInterfaces.addAll(interfaceNames)
                        }

                        checkedClasses.addAll(toCheckInterfaces)
                        toCheckInterfaces = newToCheckInterfaces.filter {
                            !checkedClasses.contains(it)
                        }
                    }
                }
            }

            // TODO we need to filter interface in APK if supports multiple APKs
            staticInvocations.forEach {
                result[it] = null
            }

            logger.debug("getAllDefaultInterfacesOfClass result ${result.keys}")
            return result
        }
    }

    @Synchronized
    fun getCoreLibraryRewriteClassMap(): Map<String, String> {
        val coreLibraryRewriteClassMap = mutableMapOf<String, String>()

        // see https://stackoverflow.com/questions/66556819/android-corelibrarydesugaring-which-java-11-apis-can-i-expect-to-work
        // see https://r8.googlesource.com/r8/+/314402df87d70a4ad2b6a075c4af0849b33c5830/src/library_desugar/desugar_jdk_libs.json
        val javaPrefix = "Lj$/"
        DriverManager.getConnection(url).use { connection ->
            runWithTimeCost("coreLibraryRewriteClassMap") {
                val sql = "SELECT name FROM class_info WHERE name like '$javaPrefix%';"
                connection.createStatement().use { statement ->
                    val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                    while (resultSet.next()) {
                        val name = resultSet.getString(1)
                        val desugaredName = name.replace(javaPrefix, "Ljava/")
                        coreLibraryRewriteClassMap[desugaredName] = name
                    }
                }
            }

            return coreLibraryRewriteClassMap
        }
    }

    @Synchronized
    fun filterDefaultInterfaces(suspectInterfaceNames: Collection<String>): Set<String> {
        logger.debug("filterDefaultInterfaces suspectInterfacesName $suspectInterfaceNames")

        val result = mutableSetOf<String>()
        DriverManager.getConnection(url).use { connection ->
            runWithTimeCost("filterDefaultInterfaces") {
                val allInterfacesString = suspectInterfaceNames.joinToString(",") { "'${it.desugarDefaultInterfaceName}','${it.desugarDefaultInterfaceName2}'" }
                val sql = "SELECT name FROM class_info WHERE name IN ($allInterfacesString);"
                connection.createStatement().use { statement ->
                    val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                    while (resultSet.next()) {
                        val name = resultSet.getString(1)
                        if (name.endsWith(desugarDefaultInterfaceSuffix)) {
                            result.add(name.interfaceNameFromDesugaredDefaultMethodClass)
                        } else if (name.endsWith(desugarDefaultInterfaceSuffix2)) {
                            result.add(name.interfaceNameFromDesugaredDefaultMethodClass2)
                        }
                    }
                }
            }

            return result
        }
    }

    @Synchronized
    fun isEnableDesugared(): Boolean {
        logger.debug("isEnableDesugared")

        var isEnableDesugared = true
        DriverManager.getConnection(url).use { connection ->
            runWithTimeCost("isEnableDesugared") {
                val sql = "SELECT is_enable_desugar FROM apk_info;"
                connection.createStatement().use { statement ->
                    val resultSet: ResultSet = statement.executeQueryAndLog(sql)
                    while (resultSet.next()) {
                        isEnableDesugared = resultSet.getBoolean(1)
                    }
                }
            }
        }

        logger.debug("isEnableDesugared $isEnableDesugared")
        return isEnableDesugared
    }

    @Synchronized
    fun getApkInfoKeys(): List<String> {
        val selectSQL = "SELECT * FROM apk_info;"
        val keys = mutableListOf<String>()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                val resultSet: ResultSet = statement.executeQueryAndLog(selectSQL)
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
                val resultSet: ResultSet = statement.executeQueryAndLog(selectSQL)
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

    /**
     * Method refs in dex can use subclass owner while implementation is declared in super/interface.
     * If hierarchy info is not available in current db snapshot, return true to avoid false-positive
     * "removed member" expansion in minify recompile check.
     */
    private fun isMethodInHierarchy(
        className: String,
        target: MethodNode,
        classInfoMap: Map<String, ClassNode>,
    ): Boolean {
        var hasUnknownParent = false
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(className)
        while (queue.isNotEmpty()) {
            val currentClass = queue.removeFirst()
            if (!visited.add(currentClass)) {
                continue
            }
            val classNode = classInfoMap[currentClass]
            if (classNode == null) {
                if (!currentClass.isIgnorableUnknownHierarchyType()) {
                    hasUnknownParent = true
                }
                continue
            }
            if (classNode.methods.any { it.name == target.name && it.desc == target.desc }) {
                return true
            }
            if (classNode.superClass.isNotEmpty() && classNode.superClass != "Ljava/lang/Object;") {
                queue.add(classNode.superClass)
            }
            classNode.interfaceNames.forEach {
                queue.add(it)
            }
        }
        return hasUnknownParent
    }

    /**
     * Field refs can also point to subclass while field is declared in super class.
     * Keep the same conservative fallback as [isMethodInHierarchy].
     */
    private fun isFieldInHierarchy(
        className: String,
        target: FieldNode,
        classInfoMap: Map<String, ClassNode>,
    ): Boolean {
        var hasUnknownParent = false
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(className)
        while (queue.isNotEmpty()) {
            val currentClass = queue.removeFirst()
            if (!visited.add(currentClass)) {
                continue
            }
            val classNode = classInfoMap[currentClass]
            if (classNode == null) {
                if (!currentClass.isIgnorableUnknownHierarchyType()) {
                    hasUnknownParent = true
                }
                continue
            }
            if (classNode.fields.any { it.name == target.name && it.type == target.type }) {
                return true
            }
            if (classNode.superClass.isNotEmpty() && classNode.superClass != "Ljava/lang/Object;") {
                queue.add(classNode.superClass)
            }
            classNode.interfaceNames.forEach {
                queue.add(it)
            }
        }
        return hasUnknownParent
    }
    
    private fun Statement.executeQueryAndLog(sql: String): ResultSet {
        logger.debug("executeQuery: $sql")
        return executeQuery(sql)
    }

    /**
     * Some JDK marker interfaces do not define methods/fields.
     * Missing them in apk DB should not suppress "removed member" detection.
     */
    private fun String.isIgnorableUnknownHierarchyType(): Boolean {
        return this == "Ljava/io/Serializable;" ||
            this == "Ljava/lang/Cloneable;" ||
            this == "Ljava/lang/annotation/Annotation;"
    }
}

/**
 * MethodNodeDb carries classId, name, and desc.
 */
private data class MethodNodeDb(
    val classId: Int,
    val name: String,
    val desc: String
)
