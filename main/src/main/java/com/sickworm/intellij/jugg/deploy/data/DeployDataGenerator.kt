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
) {

    var deployDataDatabase: IDeployDataDatabase = DeployDataDatabase(File(databaseDir, "apk"), logger.getInstance("DeployDataDatabase"))
        private set

    /**
     * Build [JuggDeployData] according to deployment history.
     */
    fun buildDeployData(
        items: List<DeployItem>,
        isWarmUp: Boolean = false,
        isNeedCheckRecompile: Boolean = true,
        isNeedCheckRecompileMinifyRemovedClass: Boolean = false,
    ): JuggDeployData {
        val changedDex = items.filter { it.type == CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(changedDex, isSkipOfficialClass = !isNeedCheckRecompileMinifyRemovedClass) // check official class minify
        val changedOverlays = items.filter { it.type == CompileOutput.Type.Res || it.type == CompileOutput.Type.Asset }
        val changedLibs = items.filter { it.type == CompileOutput.Type.NativeLib }
        return buildDeployData(parsedDex, changedOverlays, changedLibs, isWarmUp, isNeedCheckRecompile, isNeedCheckRecompileMinifyRemovedClass)
    }

    @TestOnly
    fun buildDeployData(parsedDex: ParsedDex,
                        changedOverlays: List<DeployItem>,
                        changedLibs: List<DeployItem> = emptyList(),
                        isWarmUp: Boolean = false,
                        isNeedCheckRecompile: Boolean = true,
                        isNeedCheckRecompileMinifyRemovedClass: Boolean = false,
    ): JuggDeployData {
        val startTime = System.currentTimeMillis()

        val changedClasses = parsedDex.classDeployItems
        val oldClassNodes = deployDataDatabase.getClassNodes(changedClasses.flatMap {
            it.classNodes.map(ClassNode::className)
        })
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

        val effectedSourceAndClassNodes = if (isNeedCheckRecompile) {
            val checkMinifiedRemoveClass = if (isNeedCheckRecompileMinifyRemovedClass) parsedDex else null
            deployDataDatabase.getEffectedSourceAndClass(changedMethodRef, changedFieldRef, changedAbstractClasses, checkMinifiedRemoveClass)
        } else {
            emptyList()
        }
        if (effectedSourceAndClassNodes.isNotEmpty()) {
            logger.debug("effected source and class nodes: $effectedSourceAndClassNodes")
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
        )

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("buildDeployData finish, cost ${costTime}ms.")
        return juggDeployData
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
