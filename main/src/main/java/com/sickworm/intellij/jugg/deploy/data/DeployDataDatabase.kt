package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceName
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceName2
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.util.zip.ZipFile


/**
 * Manage parsed apk data.
 */
interface IDeployDataDatabase {

    fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult>


    fun clearDeployedData()

    fun commitDeployedData(juggDeployData: JuggDeployData)

    fun isDeployedOverlaysBefore(): Boolean

    fun addFullRes(changedOverlays: List<DeployItem>, isNeedRes: Boolean, isNeedAsset: Boolean): List<DeployItem>

    fun getApkInfos(): List<ApkInfo>

    fun getClassNodes(classNames: List<String>): Map<String, ClassNode>

    /**
     * @return Map<source file name, List<class name>>
     */
    fun getEffectedSourceAndClass(changedMethodRefs: List<MethodNode>,
                                  changedFieldRefs: List<FieldNode>,
                                  changedAbstractClasses: List<ClassNode>,
                                  ): List<EffectedClassNode>

    fun getAllInterfacesWithDefaultMethod(interfaces: List<String>, staticInvocations: List<String>): List<String>

    fun isEnableDesugared(): Boolean
}

class DeployDataDatabase(private val dbDir: File, private val logger: Logger) : IDeployDataDatabase {

    private var apks = listOf<ApkInfo>()
    private val database: MutableMap<String, DeployDataDatabaseSqLiteHelper> = mutableMapOf()
    private val incDeployedDatabase = IncrementalDeployDataDatabase(logger.getInstance("IncrementalDeployDataDatabase"))

    @Synchronized
    override fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult> {
        logger.debug("initAfterInstall parsed apk start, apks: ${apks.size}, deployedItems: ${deployedItems.size}")

        classNodeDbCache.clear()
        this.apks = apks
        val startTime = System.currentTimeMillis()
        database.clear()
        val updateResults = mutableListOf<ParsedApkUpdateResult>()
        apks.forEach { apk ->
            apk.files.forEach { apkFileUnit ->
                val helper = createDbHelper(apkFileUnit.apkFile, updateResults)
                database[helper.dbFile.name] = helper
            }
        }
        incDeployedDatabase.init(deployedItems)

        // clear deprecated databases
        val newDbFiles = database.values.map { it.dbFile.path }
        val existsDbFiles = dbDir.listFiles()?.filter { it.name.endsWith(".db") }?.toList() ?: emptyList()
        existsDbFiles.forEach { dbFile ->
            if (dbFile.path !in newDbFiles) {
                val isSuccess = dbFile.delete()
                logger.debug("delete deprecated database $dbFile, isSuccess: $isSuccess")
            }
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("database all init finish, cost ${costTime}ms.")

        return updateResults
    }

    private fun createDbHelper(apkFile: File, updateResults: MutableList<ParsedApkUpdateResult>): DeployDataDatabaseSqLiteHelper {
        val dbName = DeployDataDatabaseSqLiteHelper.getApkFileDbName(apkFile)
        val dbFile = File(dbDir, dbName)
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger.getInstance("DeployDataDatabaseSqLiteHelper"))
        helper.init()
        val apkEntries = ApkParser().parseEntries(apkFile)
        logger.debug("$dbName apkEntries, dexFiles: ${apkEntries.dexFiles.size}, overlayFiles: ${apkEntries.overlayFiles.size}")
        var diffResult = helper.diffApk(apkEntries)
        var includeEntries = diffResult.includeEntries
        val allChangedDexFileSize = diffResult.removedDexFiles.size + diffResult.addedDexFiles.size + diffResult.updatedDexFiles.size
        logger.debug("$dbName diffResult $diffResult")
        if (allChangedDexFileSize > 3 || allChangedDexFileSize >= apkEntries.dexFiles.size * 0.2) {
            // If removed dex files is more than 20%, it's better to full update the apk for better database update performance.
            logger.info("$dbName database dex files changed too much (${allChangedDexFileSize}/${apkEntries.dexFiles.size}), re-parse the apk.")
            includeEntries = apkEntries
            diffResult = ParsedApkDiffResult(apkEntries)
            helper.recreateDatabase()
        } else {
            logger.debug("$dbName database dex files changed $allChangedDexFileSize, incremental update database.")
        }

        val parseStartTime = System.currentTimeMillis()
        logger.debug("$dbName parse apk start.")
        val diffParsedApk = ApkParser().parse(apkFile, includeEntries)
        logger.debug("$dbName parse apk finish, cost ${System.currentTimeMillis() - parseStartTime}ms.")
        logger.debug("diffParsedApk: $diffParsedApk")

        val updateResult = helper.saveParsedApk(diffParsedApk, diffResult)
        updateResults.add(updateResult)
        if (updateResult.isSuccess) {
            logger.debug("$dbName database init finish: $updateResult")
        } else {
            logger.warn("$dbName database init failed: $updateResult")
        }

        return helper
    }

    override fun clearDeployedData() {
        incDeployedDatabase.init(emptyList())
    }

    @Synchronized
    override fun commitDeployedData(juggDeployData: JuggDeployData) {
        classNodeDbCache.clear()
        incDeployedDatabase.commitDeployedData(juggDeployData)
    }

    @Synchronized
    override fun isDeployedOverlaysBefore(): Boolean {
        return incDeployedDatabase.isDeployedOverlaysBefore()
    }

    @Synchronized
    override fun addFullRes(changedOverlays: List<DeployItem>, isNeedRes: Boolean, isNeedAsset: Boolean): List<DeployItem> {
        TimeLogger.start("addFullRes")

        val overlays = mutableListOf<DeployItem>()
        overlays.addAll(changedOverlays)

        // Deploy all Apks' overlay resources, just like Apply Changes.
        val apkFiles = apks.flatMap { it.files }.map { it.apkFile }
        apkFiles.forEach out@{ apkFile ->
            ZipFile(apkFile).use { zipFile ->
                val databaseName = DeployDataDatabaseSqLiteHelper.getApkFileDbName(apkFile)
                val overlayInfos = database[databaseName]?.getResInfos(isNeedRes, isNeedAsset)
                    ?: throw JuggException.databaseNotFound(apkFile, databaseName)
                logger.debug("addFullRes, changedOverlays: ${changedOverlays.size}, isNeedRes: $isNeedRes, " +
                        "isNeedAsset: $isNeedAsset, overlayInfos: ${overlayInfos.size}")
                val nameSet = changedOverlays
                    .filter { it.apkPath == apkFile.path }
                    .map { it.name }
                    .toSet()
                overlayInfos.forEach {
                    if (nameSet.contains(it.name)) return@forEach
                    val path = it.name
                    val entry = zipFile.getEntry(path) ?: throw JuggInternalException.apkEntryNotFound(apkFile, path)
                    val content = zipFile.getInputStream(entry).use { inputStream ->
                        inputStream.readAllBytes()
                    }
                    val deployItem = DeployItem(
                        name = it.name,
                        type = if (it.isRes) CompileOutput.Type.Res else CompileOutput.Type.Asset,
                        checksum = it.checksum,
                        content = content,
                        apkPath = apkFile.path,
                    )
                    overlays.add(deployItem)
                }
            }
        }
        TimeLogger.end("addFullRes", logger)
        return overlays
    }

    @Synchronized
    override fun getApkInfos(): List<ApkInfo> {
        return apks
    }

    private var classNodeDbCache = mutableMapOf<String, ClassNode>()

    @Synchronized
    override fun getClassNodes(classNames: List<String>): Map<String, ClassNode> {
        if (classNames.isEmpty()) {
            return emptyMap()
        }

        val classNodes = mutableMapOf<String, ClassNode>()

        val incClassNodes = incDeployedDatabase.getClassNodes(classNames)
        classNodes.putAll(incClassNodes)

        val remainClassNodes = mutableListOf<String>()
        classNames.forEach {
            if (classNodes.containsKey(it)) {
                return@forEach
            }
            val cache = classNodeDbCache[it]
            if (cache != null) {
                classNodes[it] = cache
                return@forEach
            }
            remainClassNodes.add(it)
        }

        database.values.forEach {
            val nodes = it.getClassNodes(remainClassNodes)
            classNodes.putAll(nodes)
            classNodeDbCache.putAll(nodes)
        }

        return classNodes
    }

    @Synchronized
    override fun getEffectedSourceAndClass(
        changedMethodRefs: List<MethodNode>,
        changedFieldRefs: List<FieldNode>,
        changedAbstractClasses: List<ClassNode>,
    ): List<EffectedClassNode> {
        if (changedMethodRefs.isEmpty() && changedFieldRefs.isEmpty() && changedAbstractClasses.isEmpty()) {
            return emptyList()
        }

        val incrementalEffectClassNodes = incDeployedDatabase.getEffectedSourceAndClass(changedMethodRefs, changedFieldRefs, changedAbstractClasses)
        val effectClassNodesMap: MutableMap<String, EffectedClassNode> = incrementalEffectClassNodes.associateBy { it.className }.toMutableMap()
        database.values.forEach { helper ->
            try {
                val apkEffectClassNodesMap = helper.getEffectedClassNodes(changedMethodRefs, changedFieldRefs, changedAbstractClasses)
                apkEffectClassNodesMap.forEach addNode@{
                    // use incremental first
                    val oldNode = effectClassNodesMap[it.className]
                    val node = oldNode?.copy(effectedByClasses = oldNode.effectedByClasses + it.effectedByClasses) ?: it
                    effectClassNodesMap[it.className] = node
                }
            } catch (e: Exception) {
                logger.warn("Failed to find effected source and classes, Exception: ${e.message}")
                logger.warn("The compilation may work incorrectly.")
                return@forEach
            }
        }

        return effectClassNodesMap.values.toList()
    }

    override fun getAllInterfacesWithDefaultMethod(interfaces: List<String>, staticInvocations: List<String>): List<String> {
        val result = mutableSetOf<String>()
        val incStaticInvocationResult = incDeployedDatabase.tryFindDefaultInterfaces(emptyList(), staticInvocations)
        result.addAll(incStaticInvocationResult)

        database.values.forEach {
            val allInterfacesMap = it.getAllInterfacesOfClass(interfaces, staticInvocations, incDeployedDatabase.deployedClasses)
            val incInterfaceResult = incDeployedDatabase.tryFindDefaultInterfaces(allInterfacesMap.keys, emptyList())
            result.addAll(incInterfaceResult)
            val apkInterfaceResult = it.filterDefaultInterfaces(allInterfacesMap.keys)
            result.addAll(apkInterfaceResult)

            // finds all parent interfaces
            apkInterfaceResult.forEach { interfaceName ->
                var forbidDeadLoopCount = 1000 // avoid dead loop, but I don't think it will trigger :)
                var currentInterfaceName: String? = interfaceName
                while (forbidDeadLoopCount > 0 && currentInterfaceName != null) {
                    forbidDeadLoopCount--
                    currentInterfaceName = allInterfacesMap[currentInterfaceName]
                    if (currentInterfaceName != null) {
                        result.add(currentInterfaceName)
                    }
                }

                if (forbidDeadLoopCount == 0) {
                    logger.warn("Dead loop detected when finding all parent interfaces for $interfaceName")
                }
            }
        }
        return result.toList()
    }

    override fun isEnableDesugared(): Boolean {
        return database.values.any {
            it.isEnableDesugared()
        }
    }
}

class IncrementalDeployDataDatabase(private val logger: Logger) {

    val deployedClasses: MutableMap<String, ClassNode> = mutableMapOf()
    private val deployedOverlays: MutableMap<String, JuggFileInfo> = mutableMapOf()

    // TODO remove dirty refs for these data for more clear (no side effect for now)
    private val methodRefs: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val fieldRefs: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val subclassRefs: MutableMap<String, MutableList<String>> = mutableMapOf()

    fun init(deployedItems: List<DeployItem>) {
        deployedClasses.clear()
        deployedOverlays.clear()
        val dexDeployItems = deployedItems.filter { it.type == CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(dexDeployItems)
        parsedDex.classDeployItems.forEach { classDeployItem ->
            classDeployItem.classNodes.forEach {
                deployedClasses[it.className] = it
            }
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
        juggDeployData.parsedDex.classDeployItems.forEach { classDeployItem ->
            classDeployItem.classNodes.forEach {
                deployedClasses[it.className] = it
            }
        }
        juggDeployData.parsedDex.methodRefs.forEach {
            methodRefs.getOrPut(it.key.matchKey) { mutableListOf() }.addAll(it.value)
        }
        juggDeployData.parsedDex.fieldRefs.forEach {
            fieldRefs.getOrPut(it.key.matchKey) { mutableListOf() }.addAll(it.value)
        }
        juggDeployData.parsedDex.subclassRefs.forEach {
            subclassRefs.getOrPut(it.key) { mutableListOf() }.addAll(it.value)
        }

        juggDeployData.overlays.forEach {
            deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
        }
    }

    fun isDeployedOverlaysBefore(): Boolean {
        // if all overlays are special prefix, it means no overlay is deployed
        // notice: apply changes will detect resource.arsc or res exists.
        // so only push .jugg_* won't trigger res incremental apply.
        if (deployedOverlays.all { it.value.name.startsWith(BuildConfig.AAA_JUGG_FLAG_FILE_PREFIX) }) {
            return false
        }
        return deployedOverlays.isNotEmpty()
    }

    fun getClassNodes(classNames: Collection<String>): Map<String, ClassNode> {
        return deployedClasses.filterKeys { classNames.contains(it) }
    }

    fun getEffectedSourceAndClass(changedMethodRefs: List<MethodNode>,
                                  changedFieldRefs: List<FieldNode>,
                                  changedAbstractClasses: List<ClassNode>,
    ): List<EffectedClassNode> {
        val effectClassNodesMap = mutableMapOf<String, EffectedClassNode>()

        // changedMethodRefs and changedMethodRefs of subclasses
        val changedMethodRefsWithSubclasses = changedMethodRefs.toMutableList()

        var classesToCheckSubclasses = changedMethodRefs.map { it.owner }.toSet()
        while (classesToCheckSubclasses.isNotEmpty()) {
            val newToCheck = mutableSetOf<String>()
            classesToCheckSubclasses.forEach { superClassName ->
                subclassRefs[superClassName]?.forEach { subclassName ->
                    deployedClasses[subclassName]?.let { subclassNode ->
                        changedMethodRefsWithSubclasses.filter {
                            it.owner == superClassName
                        }.forEach { methodNode ->
                            val subclassMethodNode = MethodNode(subclassNode.className, methodNode.access, methodNode.name, methodNode.desc)
                            changedMethodRefsWithSubclasses.add(subclassMethodNode)
                            logger.debug("found subclass method node $subclassMethodNode by $superClassName")

                            val isSubclassContainsMethod = subclassNode.methods.any {
                                it.name == methodNode.name && it.desc == methodNode.desc
                            }
                            if (isSubclassContainsMethod) {
                                logger.debug("subclass $subclassName already contains method $superClassName, won't check it's subclasses")
                            } else {
                                newToCheck.add(subclassNode.className)
                            }
                        }
                    }
                }
            }
            classesToCheckSubclasses = newToCheck
        }

        changedMethodRefsWithSubclasses.forEach {
            methodRefs[it.matchKey]?.forEach { className ->
                deployedClasses[className]?.let { classNode ->
                    logger.debug("found effected source ${classNode.source} in class ${classNode.className}, ref method ${it.matchKey}")
                    val effectedClassNode = effectClassNodesMap[classNode.className] ?: EffectedClassNode(classNode.className, classNode.source, emptyList())
                    effectClassNodesMap[classNode.className] = effectedClassNode.copy(
                        effectedByClasses = effectedClassNode.effectedByClasses + it.owner
                    )
                }
            }
        }
        changedFieldRefs.forEach {
            fieldRefs[it.matchKey]?.forEach { className ->
                deployedClasses[className]?.let { classNode ->
                    logger.debug("found effected source ${classNode.source} in class ${classNode.className}, ref field ${it.matchKey}")
                    val effectedClassNode = effectClassNodesMap[classNode.className] ?: EffectedClassNode(classNode.className, classNode.source, emptyList())
                    effectClassNodesMap[classNode.className] = effectedClassNode.copy(
                        effectedByClasses = effectedClassNode.effectedByClasses + it.owner
                    )
                }
            }
        }

        var toCheckChangedAbstractClasses = changedAbstractClasses.toMutableList()
        while (toCheckChangedAbstractClasses.isNotEmpty()) {
            val newToCheckChangedAbstractClasses = mutableListOf<ClassNode>()
            toCheckChangedAbstractClasses.forEach { superClassNode ->
                subclassRefs[superClassNode.className]?.forEach { subclassName ->
                    deployedClasses[subclassName]?.let { subclassNode ->
                        if (subclassNode.isAbstract) {
                            newToCheckChangedAbstractClasses.add(subclassNode)
                        } else {
                            logger.debug("found effected source ${subclassNode.source} in class ${subclassNode.className}, ref class ${superClassNode.className}")
                            val effectedClassNode = effectClassNodesMap[subclassNode.className] ?: EffectedClassNode(subclassNode.className, subclassNode.source, emptyList())
                            effectClassNodesMap[subclassNode.className] = effectedClassNode.copy(
                                effectedByClasses = effectedClassNode.effectedByClasses + superClassNode.className
                            )
                            newToCheckChangedAbstractClasses.add(subclassNode)
                        }
                    }
                }
            }
            toCheckChangedAbstractClasses = newToCheckChangedAbstractClasses
        }

        return effectClassNodesMap.values.toList()
    }

    /**
     * @return Pair<List<founded default interfaces>, List<founded interfaces>>
     */
    fun tryFindDefaultInterfaces(interfaces: Collection<String>, staticInvocations: Collection<String>): List<String> {
        val result = mutableListOf<String>()

        staticInvocations.forEach {
            val hasDefaultMethods = deployedClasses.contains(it.desugarDefaultInterfaceName) ||
                deployedClasses.contains(it.desugarDefaultInterfaceName2)
            if (hasDefaultMethods) {
                result.add(it)
            }
        }

        var toCheckInterfaces = interfaces
        while (toCheckInterfaces.isNotEmpty()) {
            val newToCheckClasses = mutableListOf<String>()

            toCheckInterfaces.forEach { interfaceName ->
                val hasDefaultMethods = deployedClasses.contains(interfaceName.desugarDefaultInterfaceName) ||
                        deployedClasses.contains(interfaceName.desugarDefaultInterfaceName2)
                if (hasDefaultMethods) {
                    result.add(interfaceName)
                }
                deployedClasses[interfaceName]?.let { classNode ->
                    newToCheckClasses.addAll(classNode.interfaceNames)
                }
            }
            toCheckInterfaces = newToCheckClasses
        }

        return result
    }

    private val MethodNode.matchKey get() = "${owner}.${name}${desc}"

    private val FieldNode.matchKey get() = "${owner}.${name}"
}