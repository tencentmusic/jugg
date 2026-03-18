package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import org.jetbrains.annotations.TestOnly
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Generate [JuggDeployData] according to deployment history.
 */
class DeployDataGenerator(
    private val logger: Logger,
    databaseDir: File,
    private val constRefEffectProvider: ConstRefEffectProvider = ConstRefEffectProvider.NO_OP,
) {

    var deployDataDatabase: IDeployDataDatabase = DeployDataDatabase(File(databaseDir, "apk"), logger.getInstance("DeployDataDatabase"))
        private set

    var mappingFile: File? = null

    /**
     * Build [JuggDeployData] according to deployment history.
     */
    fun buildDeployData(
        items: List<DeployItem>,
        isWarmUp: Boolean = false,
        isNeedCheckRecompile: Boolean = true,
        isNeedCheckRecompileMinifyRemovedClass: Boolean = false,
        isCompilingEffectedSourceFiles: Boolean = false,
        constRefChangedSourcePaths: List<String> = emptyList(),
    ): JuggDeployData {
        val changedDex = items.filter { it.type == CompileOutput.Type.Dex }
        logger.trace("[PERF] parseDex start, thread=${Thread.currentThread().name}, dexCount=${changedDex.size}")
        val parseDexStart = System.currentTimeMillis()
        val parsedDex = ApkParser().parseDex(changedDex, isSkipOfficialClass = !isNeedCheckRecompileMinifyRemovedClass) // check official class minify
        logger.trace("[PERF] parseDex end, cost=${System.currentTimeMillis() - parseDexStart}ms, thread=${Thread.currentThread().name}")
        val changedOverlays = items.filter { it.type == CompileOutput.Type.Res || it.type == CompileOutput.Type.Asset }
        val changedLibs = items.filter { it.type == CompileOutput.Type.NativeLib }
        return buildDeployData(
            parsedDex = parsedDex,
            changedOverlays = changedOverlays,
            changedLibs = changedLibs,
            isWarmUp = isWarmUp,
            isNeedCheckRecompile = isNeedCheckRecompile,
            isNeedCheckRecompileMinifyRemovedClass = isNeedCheckRecompileMinifyRemovedClass,
            isCompilingEffectedSourceFiles = isCompilingEffectedSourceFiles,
            constRefChangedSourcePaths = constRefChangedSourcePaths,
        )
    }

    @TestOnly
    fun buildDeployData(parsedDex: ParsedDex,
                        changedOverlays: List<DeployItem>,
                        changedLibs: List<DeployItem> = emptyList(),
                        isWarmUp: Boolean = false,
                        isNeedCheckRecompile: Boolean = true,
                        isNeedCheckRecompileMinifyRemovedClass: Boolean = false,
                        isCompilingEffectedSourceFiles: Boolean = false,
                        constRefChangedSourcePaths: List<String> = emptyList(),
    ): JuggDeployData {
        val startTime = System.currentTimeMillis()

        val changedClasses = parsedDex.classDeployItems
        logger.trace("[PERF] getClassNodes start, thread=${Thread.currentThread().name}, classCount=${changedClasses.flatMap { it.classNodes.map(ClassNode::className) }.size}")
        val getClassNodesStart = System.currentTimeMillis()
        val oldClassNodes = deployDataDatabase.getClassNodes(changedClasses.flatMap {
            it.classNodes.map(ClassNode::className)
        })
        logger.trace("[PERF] getClassNodes end, cost=${System.currentTimeMillis() - getClassNodesStart}ms, thread=${Thread.currentThread().name}, resultSize=${oldClassNodes.size}")
        val newClasses = mutableListOf<ClassDeployItem>()
        val hotReloadModifiedClasses = mutableListOf<ClassDeployItem>()
        val hotFixModifiedClasses = mutableListOf<ClassDeployItem>()
        val changedMethodRef = mutableListOf<MethodNode>()
        val changedFieldRef = mutableListOf<FieldNode>()
        val changedAbstractClasses = mutableListOf<ClassNode>()
        val deletedNormalMethodClasses = mutableListOf<ClassNode>()
        changedClasses.forEach changedClasses@{
            if (it.isMultipleDex || it.isLibraryDex) {
                logger.debug("deploy dex ${it.name} is multiple dex / library dex, need hot fix.")
                hotFixModifiedClasses.add(it)
            }
            it.classNodes.forEach classNodes@{ newClassNode ->
                val className = newClassNode.className
                val oldClassNode: ClassNode? = oldClassNodes[className]
                if (oldClassNode == null) {
                    newClasses.add(it)
                    return@classNodes
                }

                // compare class node difference
                val result = ClassNodeComparator(oldClassNode, newClassNode).compare()

                if (result.isCanHotReload) {
                    logger.debug("class $className structure not changed: $result")
                } else {
                    logger.debug("class $className structure changed, need hot fix: $result")
                }
                if (!(it.isMultipleDex || it.isLibraryDex)) {
                    if (result.isCanHotReload) {
                        // no breaking changes, hot reload
                        hotReloadModifiedClasses.add(it)
                    } else {
                        // different structure, hot fix
                        hotFixModifiedClasses.add(it)
                    }
                }

                val isRSubClass = oldClassNode.isRSubClass
                if (isRSubClass) {
                    // reason why R subclass don't add effected methods and fields:
                    // 1. R subclass will only add constant fields.
                    // 2. R subclass may print lots of added fields after handled by RDexForSubmoduleCompiler for gradle submodule.
                    // 3. (important) R subclass will delete lots of fields after handled by RFileFixer for gradle submodule,
                    //    which will trigger most of the source files that reference R to be recompiled!
                    logger.debug("class $className is R subclass, don't add effected methods and fields.")
                    return@classNodes
                }

                // we don't care about abstract, because it won't affect class bytecode.
                // ignore abstract can stop recompile when redex interface class default method (which will make methods be not abstract)
                changedMethodRef.addAll(result.effectMethods)
                changedFieldRef.addAll(result.deletedFields)
                if (result.isAddedAbstractMethodForNonAbstractClass) {
                    changedAbstractClasses.add(newClassNode)
                }

                val deletedNormalMethod = result.effectMethods.filter { method ->
                    !method.name.contains("$")
                }
                if (deletedNormalMethod.isNotEmpty()) {
                    deletedNormalMethodClasses.add(newClassNode)
                }
            }
        }

        var overlays = changedOverlays
        logger.debug("changedOverlays: $overlays")
        val isFullRes = isWarmUp || (overlays.isNotEmpty() && !deployDataDatabase.isDeployedOverlaysBefore())
        if (isFullRes) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")
            val costTime = measureTimeMillis {
                overlays = deployDataDatabase.addFullRes(overlays, isNeedRes = true, isNeedAsset = false)
            }
            logger.debug("first time deploy overlay, need full deployment finish, cost ${costTime}ms")
        }

        logger.trace("[PERF] getEffectedSourceAndClass start, thread=${Thread.currentThread().name}, isNeedCheckRecompile=$isNeedCheckRecompile")
        val getEffectedStart = System.currentTimeMillis()
        val effectedSourceAndClassNodes = if (isNeedCheckRecompile) {
            val checkMinifiedRemoveClass = if (isNeedCheckRecompileMinifyRemovedClass) parsedDex else null
            val effectedNodes = deployDataDatabase.getEffectedSourceAndClass(
                changedMethodRef, changedFieldRef, changedAbstractClasses,
                checkMinifiedRemoveClass).toMutableList()
            logger.trace("[PERF] getEffectedSourceAndClass db query end, cost=${System.currentTimeMillis() - getEffectedStart}ms, thread=${Thread.currentThread().name}")

            // Check for method inlining effects if we're checking minified removed classes
            // for effected source files, no need to detect because logic is not changed, no need to update inlined codes.
            if (!isCompilingEffectedSourceFiles) {
                val inlineDetector = InlineMethodDetector(mappingFile, logger.getInstance("InlineMethodDetector"))
                val inlineEffectedNodes = inlineDetector.findInlineEffectedClasses(checkMinifiedRemoveClass)
                merge(effectedNodes, inlineEffectedNodes)
            }

            effectedNodes
        } else {
            emptyList()
        }
        logger.trace("[PERF] getEffectedSourceAndClass total end, cost=${System.currentTimeMillis() - getEffectedStart}ms, thread=${Thread.currentThread().name}")
        if (effectedSourceAndClassNodes.isNotEmpty()) {
            logger.debug("effected source and class nodes: $effectedSourceAndClassNodes")
        }
        logger.trace("[PERF] constRefEffectProvider start, thread=${Thread.currentThread().name}, isNeedCheckRecompile=$isNeedCheckRecompile")
        val constRefStart = System.currentTimeMillis()
        val constRefEffectedSourcePaths = if (isNeedCheckRecompile) {
            val readiness = try {
                constRefEffectProvider.ensureReadyForRecompile(constRefChangedSourcePaths)
            } catch (t: Throwable) {
                logger.warn("const ref readiness check failed, fallback to completed cache only", t)
                ConstRefReadiness(isReady = false)
            }
            logger.trace("[PERF] constRefEffectProvider.ensureReadyForRecompile end, cost=${System.currentTimeMillis() - constRefStart}ms, thread=${Thread.currentThread().name}")
            if (!readiness.isReady) {
                logger.debug(
                    "const ref analysis not ready details, " +
                        "unreadyPaths=${readiness.unreadyPaths}, pendingSourceDirs=${readiness.pendingSourceDirs}"
                )
                logger.warn(
                    "const ref analysis not ready, fallback to completed cache only, " +
                        "unreadyPathCount=${readiness.unreadyPaths.size}, " +
                        "pendingSourceDirCount=${readiness.pendingSourceDirs.size}"
                )
            }
            val getEffectedFilesStart = System.currentTimeMillis()
            try {
                constRefEffectProvider
                    .getEffectedFiles(constRefChangedSourcePaths)
                    .map { it.refFilePath }
                    .distinct()
            } catch (t: Throwable) {
                logger.warn("const ref effected files query failed, fallback to empty result", t)
                emptyList()
            }.also {
                logger.trace("[PERF] constRefEffectProvider.getEffectedFiles end, cost=${System.currentTimeMillis() - getEffectedFilesStart}ms, thread=${Thread.currentThread().name}")
            }
        } else {
            emptyList()
        }
        logger.trace("[PERF] constRefEffectProvider total end, cost=${System.currentTimeMillis() - constRefStart}ms, thread=${Thread.currentThread().name}")
        if (constRefEffectedSourcePaths.isNotEmpty()) {
            logger.debug("const ref effected source files: $constRefEffectedSourcePaths")
        }

        val apks = deployDataDatabase.getApkInfos()

        // collect files that need to update to APK and resign, reinstall
        val updateApkFiles = changedOverlays.filter {
            val isAndroidManifest = it.type == CompileOutput.Type.Res && it.name == "AndroidManifest.xml"
            return@filter isAndroidManifest
        }.toMutableList()
        if (updateApkFiles.any { it.name == "AndroidManifest.xml" }) {
            // add resources.arsc too
            val resourcesArsc = overlays.find { it.name == "resources.arsc" }
            if (resourcesArsc != null) {
                updateApkFiles += resourcesArsc
            }
        }
        updateApkFiles += changedLibs

        val juggDeployData = JuggDeployData(apks,
            newClasses, hotFixModifiedClasses, hotReloadModifiedClasses,
            effectedSourceAndClassNodes,
            overlays, parsedDex,
            isFullRes, isWarmUp,
            updateApkFiles = updateApkFiles,
            constRefEffectedSourcePaths = constRefEffectedSourcePaths,
        )

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("buildDeployData finish, cost ${costTime}ms.")
        return juggDeployData
    }

    private fun merge(effectedNodes: MutableList<EffectedClassNode>, inlineEffectedNodes: List<EffectedClassNode>) {
        // Merge inline effected nodes with existing effected nodes
        inlineEffectedNodes.forEach { inlineNode ->
            val existing = effectedNodes.find { it.className == inlineNode.className }
            if (existing != null) {
                val updated = existing.copy(
                    effectedByClasses = (existing.effectedByClasses + inlineNode.effectedByClasses).distinct(),
                    // Inline impact should be handled by DexMinifyCompiler, not source recompile.
                    effectedType = EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE,
                )
                effectedNodes[effectedNodes.indexOf(existing)] = updated
            } else {
                effectedNodes.add(inlineNode)
            }
        }
    }

    fun getDesugarInfo(classFiles: List<CompileFile>, apkFile: File): DesugarInfo {
        TimeLogger.start("getDesugarInfo")
        val files = classFiles.map { it.file }
        val parser = ClassFileParser(files)
        parser.parse()

        val allInterfacesWithDefaultMethod =  deployDataDatabase.getAllInterfacesWithDefaultMethod(
            parser.interfaces.toList(), parser.staticInvocationRefs.toList()
        )
        val coreLibraryRewriteClassMap = deployDataDatabase.getCoreLibraryRewriteClassMap(apkFile)

        val isNeedRewriteCoreLibrary = coreLibraryRewriteClassMap.isNotEmpty()
        TimeLogger.end("getDesugarInfo", logger)

        return DesugarInfo(
            allInterfacesWithDefaultMethod,
            coreLibraryRewriteClassMap,
            isNeedRewriteCoreLibrary,
            null
        )
    }

    @TestOnly
    fun getAllInterfacesWithDefaultMethod(interfaces: List<String>, staticInvocations: List<String>): List<String> {
        return deployDataDatabase.getAllInterfacesWithDefaultMethod(interfaces, staticInvocations)
    }

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>) {
        deployDataDatabase.init(apks, deployedItems)
    }

    @Synchronized
    fun clearDeployedData() {
        deployDataDatabase.clearDeployedData()
    }

    /**
     * Mark [juggDeployData] as deployed.
     */
    @Synchronized
    fun commitDeployedData(juggDeployData: JuggDeployData) {
        deployDataDatabase.commitDeployedData(juggDeployData)
    }

    @Synchronized
    fun isEnableDesugared(): Boolean {
        return deployDataDatabase.isEnableDesugared()
    }

    private val ClassNode.isRSubClass: Boolean get() {
        val classSimpleName = className.substringAfterLast("/")
        return classSimpleName.startsWith("R$")
    }
}
