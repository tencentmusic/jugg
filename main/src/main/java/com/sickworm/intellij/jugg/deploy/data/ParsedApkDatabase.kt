package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.util.zip.ZipFile


/**
 * Manage parsed apk data.
 */
interface IParsedApkDatabase {

    fun init(apks: List<ApkInfo>): List<ParsedApkUpdateResult>

    fun getFullOverlays(changedOverlays: List<DeployItem>): List<DeployItem>

    fun getApkInfos(): List<ApkInfo>

    fun getClassNodes(classNames: List<String>): Map<String, ClassNode>
}

class ParsedApkDatabase(private val dbDir: File, private val logger: Logger) : IParsedApkDatabase {

    private var apks = listOf<ApkInfo>()
    private val database: MutableMap<String, ParsedApkDatabaseSqLiteHelper> = mutableMapOf()

    @Synchronized
    override fun init(apks: List<ApkInfo>): List<ParsedApkUpdateResult> {
        this.apks = apks
        val startTime = System.currentTimeMillis()
        database.clear()
        val updateResults = mutableListOf<ParsedApkUpdateResult>()
        apks.forEach {
            val dbFile = File(dbDir, it.applicationId + ".db")
            val helper = ParsedApkDatabaseSqLiteHelper(dbFile, logger)
            helper.init()
            val apkOverlays = ApkParser().parseEntries(it)
            val diffResult = helper.diffApk(apkOverlays)
            val diffParsedApk = ApkParser().parse(it, diffResult.includeEntries)
            val updateResult = helper.saveParsedApk(diffParsedApk, diffResult)
            updateResults.add(updateResult)
            if (updateResult.isSuccess) {
                logger.debug("${it.applicationId} database init finish: $updateResult")
            } else {
                logger.warn("${it.applicationId} database init failed: $updateResult")
            }

            database[it.applicationId] = helper
        }
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("database all init finish, cost ${costTime}ms.")

        return updateResults
    }

    @Synchronized
    override fun getFullOverlays(changedOverlays: List<DeployItem>): List<DeployItem> {
        val nameSet = changedOverlays.map { it.name }.toSet()
        val overlays = mutableListOf<DeployItem>()
        overlays.addAll(changedOverlays)
        val overlayInfos = database.values.flatMap { it.getOverlayInfos() }
        overlayInfos.forEach {
            if (nameSet.contains(it.name)) return@forEach
            val deployItem = DeployItem(
                name = it.name,
                type = CompileOutput.Type.Overlay,
                checksum = it.checksum,
                content = readFileContentFromApk(apks.first().files.first().apkFile, it.name)
            )
            overlays.add(deployItem)
        }
        return overlays
    }

    private fun readFileContentFromApk(apk: File, path: String): ByteArray {
        val zipFile = ZipFile(apk)
        val entry = zipFile.getEntry(path) ?: throw JuggInternalException.apkEntryNotFound(apk, path)
        val inputStream = zipFile.getInputStream(entry)
        return inputStream.readAllBytes()
    }

    @Synchronized
    override fun getApkInfos(): List<ApkInfo> {
        return apks
    }

    @Synchronized
    override fun getClassNodes(classNames: List<String>): Map<String, ClassNode> {
        val classNodes = mutableMapOf<String, ClassNode>()
        database.values.forEach {
            val nodes = it.getClassNodes(classNames)
            classNodes.putAll(nodes)
        }
        return classNodes
    }
}


