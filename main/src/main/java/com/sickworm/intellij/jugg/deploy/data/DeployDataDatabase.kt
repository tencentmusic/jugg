package com.sickworm.intellij.jugg.deploy.data

import com.googlecode.d2j.DexConstants
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.compiler.*
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
 * IDeployDataDatabase defines APK-parsing persistence and impact-query APIs used by deploy planning.
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
                                  maybeMinifiedRemoveClasses: ParsedDex?,
                                  ): List<EffectedClassNode>

    fun getAllInterfacesWithDefaultMethod(interfaces: List<String>, staticInvocations: List<String>): List<String>

    fun getCoreLibraryRewriteClassMap(apkFile: File): Map<String, String>

    fun isEnableDesugared(): Boolean
}

/**
 * DeployDataDatabase manages per-application deploy databases, parses APK updates, and answers class/method/field impact queries.
 * Collaboration: Builds and queries app-scoped SQLite state via [DeployDataDatabaseSqLiteHelper], parses APK deltas via [ApkParserProcessLauncher]/[ApkParser], and tracks already deployed overlays in [IncrementalDeployDataDatabase].
 * Data Contract: [init] groups inputs by [ApkInfo.applicationId] and keeps one `.db` per app id; stale database files are removed after successful init; all public entrypoints are synchronized to avoid concurrent mutation.
 */
class DeployDataDatabase(private val dbDir: File, private val logger: Logger) : IDeployDataDatabase {

    private var apks = listOf<ApkInfo>()
    private val database: MutableMap<String, DeployDataDatabaseSqLiteHelper> = mutableMapOf()
    private val incDeployedDatabase = IncrementalDeployDataDatabase(logger.getInstance("IncrementalDeployDataDatabase"))

    @Synchronized
    override fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult> {
        // forbid concurrent running to avoid IDE stuck
        synchronized(DeployDataDatabase::class) {
            return doInit(apks, deployedItems)
        }
    }

    @Synchronized
    fun doInit(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult> {
        logger.debug("initAfterInstall parsed apk start, apks: ${apks.size}, deployedItems: ${deployedItems.size}")

        classNodeDbCache.clear()
        this.apks = apks
        val startTime = System.currentTimeMillis()
        database.clear()
        val updateResults = mutableListOf<ParsedApkUpdateResult>()

        val newDbFiles = mutableListOf<String>()
        apks.groupBy { it.applicationId }
            .mapValues { entry -> entry.value.flatMap { it.files } }
            .forEach { (applicationId, apkFileUnits) ->
                val dbFile = File(dbDir, "$applicationId.db")
                newDbFiles += dbFile.path
                val helper = database[applicationId] ?: DeployDataDatabaseSqLiteHelper(dbFile, logger.getInstance("DeployDataDatabaseSqLiteHelper")).also {
                    it.init()
                    database[applicationId] = it
                }
                updateResults.addAll(processApkWithHelper(applicationId, apkFileUnits, helper))
        }
        incDeployedDatabase.init(deployedItems)

        // clear deprecated databases
        // remove deprecated db files not in current applicationId set
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

    private fun processApkWithHelper(
        applicationId: String,
        apkFiles: List<ApkFileUnit>,
        helper: DeployDataDatabaseSqLiteHelper
    ): List<ParsedApkUpdateResult> {
        // Determine whether to use an independent process for parsing.
        val launcher = ApkParserProcessLauncher(logger)
        val useIsolatedProcess = launcher.shouldUseIsolatedProcess(apkFiles)

        return if (useIsolatedProcess) {
            logger.info("APK size exceeds threshold, using isolated process for parsing")
            try {
                launcher.parseInIsolatedProcess(dbDir, apkFiles, applicationId)
            } catch (e: Exception) {
                logger.warn("Isolated process parsing failed, fallback to in-process parsing: ${e.message}")
                processApkInCurrentProcess(apkFiles, helper)
            }
        } else {
            logger.debug("APK size is small, using in-process parsing")
            processApkInCurrentProcess(apkFiles, helper)
        }
    }

    private fun processApkInCurrentProcess(
        apkFiles: List<ApkFileUnit>,
        helper: DeployDataDatabaseSqLiteHelper
    ): List<ParsedApkUpdateResult> {
        val diffBeanList = apkFiles.map { apkFileUnit ->
            val apkFile = apkFileUnit.apkFile
            val apkEntries = ApkParser().parseEntries(apkFile)
            logger.debug("${apkFile.name} apkEntries, dexFiles: ${apkEntries.dexFiles.size}, overlayFiles: ${apkEntries.overlayFiles.size}")
            val diffResult = helper.diffApk(apkEntries)
            logger.debug("${apkFile.name} diffResult $diffResult")
            DiffBean(apkEntries, diffResult)
        }.toMutableList()

        val allChangedDexFileSize = diffBeanList.sumOf { it.allChangedDexFileSize }
        val dexFileSize = diffBeanList.sumOf { it.dexFileSize }
        val isFullUpdate = allChangedDexFileSize > 3 || (dexFileSize > 0 && allChangedDexFileSize >= dexFileSize * 0.2)

        val diffs = diffBeanList.map { if (isFullUpdate) ParsedApkDiffResult(it.apkEntries) else it.diffResult }
        TimeLogger.start("ApkParser.parse")
        val parsedList = ApkParser().parse(diffs)
        TimeLogger.end("ApkParser.parse", logger)

        if (isFullUpdate) {
            logger.info("${apkFiles.map { it.apkFile.name }} dex changes too much (${allChangedDexFileSize}/$dexFileSize), full update this APK.")
            helper.recreateDatabase()
        } else {
            logger.debug("${apkFiles.map { it.apkFile.name }} incremental update database.")
        }

        TimeLogger.start("saveParsedApkBatch")
        val result = helper.saveParsedApkBatch(parsedList, diffs)
        TimeLogger.end("saveParsedApkBatch", logger)
        return result
    }

    /**
     * DiffBean carries apkEntries and diffResult.
     */
    private data class DiffBean(
        val apkEntries: ApkEntries,
        val diffResult: ParsedApkDiffResult,
    ) {
        val allChangedDexFileSize = diffResult.removedDexFiles.size + diffResult.addedDexFiles.size + diffResult.updatedDexFiles.size
        val dexFileSize = apkEntries.dexFiles.size
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
                val applicationId = apks.find { info -> info.files.any { it.apkFile == apkFile } }?.applicationId
                    ?: throw JuggException.databaseNotFound(apkFile, "unknown")
                val helper = database[applicationId]
                    ?: throw JuggException.databaseNotFound(apkFile, "$applicationId.db")
                val overlayInfos = helper.getResInfos(apkFile, isNeedRes, isNeedAsset)
                logger.debug("addFullRes for $apkFile, changedOverlays: ${changedOverlays.size}, isNeedRes: $isNeedRes, " +
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
        maybeMinifiedRemoveClasses: ParsedDex?,
    ): List<EffectedClassNode> {
        if (changedMethodRefs.isEmpty() && changedFieldRefs.isEmpty() && changedAbstractClasses.isEmpty() && maybeMinifiedRemoveClasses == null) {
            return emptyList()
        }

        val incrementalEffectClassNodes = incDeployedDatabase.getEffectedSourceAndClass(changedMethodRefs, changedFieldRefs, changedAbstractClasses)
        val effectClassNodesMap: MutableMap<String, EffectedClassNode> = incrementalEffectClassNodes.associateBy { it.className }.toMutableMap()
        val minifyEffectClassNodes = mutableMapOf<String, EffectedClassNode>()
        database.values.forEach { helper ->
            try {
                val apkEffectClassNodesMap = helper.getEffectedClassNodes(changedMethodRefs, changedFieldRefs, changedAbstractClasses)
                apkEffectClassNodesMap.forEach addNode@{
                    // use incremental first
                    val oldNode = effectClassNodesMap[it.className]
                    val node = oldNode?.copy(effectedByClasses = oldNode.effectedByClasses + it.effectedByClasses) ?: it
                    effectClassNodesMap[it.className] = node
                }

                val apkMinifyEffectClassNodesMap = helper.getEffectedClassNodesForMinify(
                    maybeMinifiedRemoveClasses, incDeployedDatabase.deployedClasses.keys.toSet(),
                )
                apkMinifyEffectClassNodesMap.forEach addNode@{
                    val oldNode = effectClassNodesMap[it.className]
                    val node = oldNode?.copy(effectedByClasses = oldNode.effectedByClasses + it.effectedByClasses) ?: it
                    minifyEffectClassNodes[it.className] = node
                }
            } catch (e: Exception) {
                logger.warn("Failed to find effected source and classes, Exception: ${e.message}")
                logger.warn("The compilation may work incorrectly.")
                return@forEach
            }
        }

        return effectClassNodesMap.values.toList() + minifyEffectClassNodes.values.toList()
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

    private val desugarInfoCache = mutableMapOf<String, Map<String, String>>()

    override fun getCoreLibraryRewriteClassMap(apkFile: File): Map<String, String> {
        desugarInfoCache[apkFile.path]?.let { return it }

        val applicationId = apks.find { info -> info.files.any { it.apkFile == apkFile } }?.applicationId
        val helper = database[applicationId]
        val result = helper?.getCoreLibraryRewriteClassMap() ?: emptyMap()
        desugarInfoCache[apkFile.path] = result
        return result
    }

    override fun isEnableDesugared(): Boolean {
        return database.values.any { it.isEnableDesugared() }
    }
}

/**
 * IncrementalDeployDataDatabase keeps the in-memory snapshot of deployed classes/overlays and updates reference indexes after each deploy.
 */
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

        // changedMethodRefs and changedMethodRefs of subclasses.
        // Static methods have no subclass dispatch semantics, so exclude them from subclass propagation.
        val changedMethodRefsWithSubclasses = changedMethodRefs.toMutableList()

        var classesToCheckSubclasses = changedMethodRefs
            .filter { it.access == MethodNode.MISS_ACCESS || (it.access and DexConstants.ACC_STATIC) == 0 }
            .map { it.owner }.toSet()
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
                    val effectedClassNode = effectClassNodesMap[classNode.className] ?: EffectedClassNode(classNode.className, classNode.source, emptyList(), EffectedClassNode.EffectedType.SOURCE)
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
                    val effectedClassNode = effectClassNodesMap[classNode.className] ?: EffectedClassNode(classNode.className, classNode.source, emptyList(), EffectedClassNode.EffectedType.SOURCE)
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
                            val effectedClassNode = effectClassNodesMap[subclassNode.className] ?: EffectedClassNode(subclassNode.className, subclassNode.source, emptyList(), EffectedClassNode.EffectedType.SOURCE)
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
