package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.util.zip.ZipFile


/**
 * Manage parsed apk data.
 */
interface IDeployDataDatabase {

    fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult>

    fun commitDeployedData(juggDeployData: JuggDeployData)

    fun isDeployedOverlaysBefore(): Boolean

    fun getFullOverlays(changedOverlays: List<DeployItem>): List<DeployItem>

    fun getApkInfos(): List<ApkInfo>

    fun getClassNodes(classNames: List<String>): Map<String, ClassNode>

    /**
     * @return Map<source file name, List<class name>>
     */
    fun getEffectedSourceAndClass(changedMethodRefs: List<MethodNode>, changedFieldRefs: List<FieldNode>): Map<String, List<String>>
}

class DeployDataDatabase(private val dbDir: File, private val logger: Logger) : IDeployDataDatabase {

    private var apks = listOf<ApkInfo>()
    private val database: MutableMap<String, DeployDataDatabaseSqLiteHelper> = mutableMapOf()
    private val incDeployedDatabase = IncrementalDeployDataDatabase()

    @Synchronized
    override fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult> {
        logger.debug("initAfterInstall parsed apk start, apks: $apks, deployedItems: ${deployedItems.size}")

        this.apks = apks
        val startTime = System.currentTimeMillis()
        database.clear()
        val updateResults = mutableListOf<ParsedApkUpdateResult>()
        apks.forEach {
            val dbFile = File(dbDir, it.applicationId + ".db")
            val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)
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
        incDeployedDatabase.init(deployedItems)

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("database all init finish, cost ${costTime}ms.")

        return updateResults
    }

    override fun commitDeployedData(juggDeployData: JuggDeployData) {
        incDeployedDatabase.commitDeployedData(juggDeployData)
    }

    override fun isDeployedOverlaysBefore(): Boolean {
        return incDeployedDatabase.isDeployedOverlaysBefore()
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

        val incClassNodes = incDeployedDatabase.getClassNodes(classNames)
        classNodes.putAll(incClassNodes)

        val remainClassNodes = classNames.filter { !classNodes.containsKey(it) }
        database.values.forEach {
            val nodes = it.getClassNodes(remainClassNodes)
            classNodes.putAll(nodes)
        }

        return classNodes
    }

    override fun getEffectedSourceAndClass(changedMethodRefs: List<MethodNode>, changedFieldRefs: List<FieldNode>): Map<String, List<String>> {
        val incrementalEffectClassNodes = incDeployedDatabase.getEffectedSourceAndClass(changedMethodRefs, changedFieldRefs)
        val effectClassNodesMap = incrementalEffectClassNodes.toMutableMap()
        database.values.forEach { helper ->
            val apkEffectClassNodesMap = helper.getEffectedClassNodes(changedMethodRefs, changedFieldRefs)
            apkEffectClassNodesMap.forEach addNode@{
                // use incremental first
                effectClassNodesMap.putIfAbsent(it.key, it.value)
            }
        }

        return effectClassNodesMap
    }
}

class IncrementalDeployDataDatabase {

    private val deployedClasses: MutableMap<String, ClassNode> = mutableMapOf()
    private val deployedOverlays: MutableMap<String, JuggFileInfo> = mutableMapOf()

    private val methodRefs: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val fieldRefs: MutableMap<String, MutableList<String>> = mutableMapOf()

    fun init(deployedItems: List<DeployItem>) {
        deployedClasses.clear()
        deployedOverlays.clear()
        val dexDeployItems = deployedItems.filter { it.type == CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(dexDeployItems)
        parsedDex.classDeployItems.forEach {
            deployedClasses[it.name] = it.classNode
        }
        parsedDex.methodRefs.forEach {
            methodRefs.getOrPut(it.key.matchKey) { mutableListOf() }.addAll(it.value)
        }
        parsedDex.fieldRefs.forEach {
            fieldRefs.getOrPut(it.key.matchKey) { mutableListOf() }.addAll(it.value)
        }

        val overlayDeployItems = deployedItems.filter { it.type != CompileOutput.Type.Dex }
        overlayDeployItems.forEach {
            deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
        }
    }

    fun commitDeployedData(juggDeployData: JuggDeployData) {
        juggDeployData.parsedDex.classDeployItems.forEach {
            deployedClasses[it.name] = it.classNode
        }
        juggDeployData.parsedDex.methodRefs.forEach {
            methodRefs.getOrPut(it.key.matchKey) { mutableListOf() }.addAll(it.value)
        }
        juggDeployData.parsedDex.fieldRefs.forEach {
            fieldRefs.getOrPut(it.key.matchKey) { mutableListOf() }.addAll(it.value)
        }

        juggDeployData.overlays.forEach {
            deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
        }
    }

    fun isDeployedOverlaysBefore(): Boolean {
        return deployedOverlays.isNotEmpty()
    }

    fun getClassNodes(classNames: List<String>): Map<String, ClassNode> {
        return deployedClasses.filterKeys { classNames.contains(it) }
    }

    fun getEffectedSourceAndClass(changedMethodRefs: List<MethodNode>, changedFieldRefs: List<FieldNode>): Map<String, List<String>> {
        val effectClassNodesMap = mutableMapOf<String, List<String>>()
        changedMethodRefs.forEach {
            methodRefs[it.matchKey]?.forEach { className ->
                deployedClasses[className]?.let { classNode ->
                    effectClassNodesMap.getOrPut(classNode.source) { mutableListOf() }
                }
            }
        }
        changedFieldRefs.forEach {
            fieldRefs[it.matchKey]?.forEach { className ->
                deployedClasses[className]?.let { classNode ->
                    effectClassNodesMap.getOrPut(classNode.source) { mutableListOf() }
                }
            }
        }

        return effectClassNodesMap
    }

    private val MethodNode.matchKey get() = "${owner}.${name}${desc}"

    private val FieldNode.matchKey get() = "${owner}.${name}"
}