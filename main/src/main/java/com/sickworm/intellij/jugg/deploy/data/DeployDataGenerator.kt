package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
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

    private var deployDataDatabase: IDeployDataDatabase = DeployDataDatabase(File(databaseDir, "apk"), logger.getInstance("DeployDataDatabase"))

    /**
     * Build [JuggDeployData] according to deployment history.
     */
    fun buildDeployData(
        items: List<DeployItem>,
        isWarmUp: Boolean = false,
        isNeedCheckRecompile: Boolean = true,
        isRecompilation: Boolean = false,
    ): JuggDeployData {
        val changedDex = items.filter { it.type == CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(changedDex)
        val changedOverlays = items.filter { it.type == CompileOutput.Type.Res || it.type == CompileOutput.Type.Asset }
        return buildDeployData(parsedDex, changedOverlays, isWarmUp, isNeedCheckRecompile, isRecompilation)
    }

    @TestOnly
    fun buildDeployData(parsedDex: ParsedDex,
                        changedOverlays: List<DeployItem>,
                        isWarmUp: Boolean = false,
                        isNeedCheckRecompile: Boolean = true,
                        @Suppress("UNUSED_PARAMETER")
                        isRecompilation: Boolean = false,
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
        changedClasses.forEach {
            if (it.isMultipleDex) {
                logger.debug("deploy dex ${it.name} is multiple dex, need hot fix.")
                hotFixModifiedClasses.add(it)
                return@forEach
            }

            val newClassNode = it.classNodes.first()
            val className = newClassNode.className
            val oldClassNode: ClassNode? = oldClassNodes[className]
            if (oldClassNode == null) {
                newClasses.add(it)
                return@forEach
            }

            // compare class node difference
            val result = ClassNodeComparator(oldClassNode, newClassNode).compare()
            if (result.isCanHotReload) {
                // no breaking changes, hot reload
                logger.debug("class $className structure not changed: $result")
                hotReloadModifiedClasses.add(it)
            } else {
                // different structure, hot fix
                logger.debug("class $className structure changed, need hot fix: $result")
                hotFixModifiedClasses.add(it)
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

        var overlays = changedOverlays
        val isFullRes = isWarmUp || (overlays.isNotEmpty() && !deployDataDatabase.isDeployedOverlaysBefore())
        if (isFullRes) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")
            val costTime = measureTimeMillis {
                overlays = deployDataDatabase.addFullRes(overlays)
            }
            logger.debug("first time deploy overlay, need full deployment finish, cost ${costTime}ms")
        }

        val effectedSourceAndClassNodes = if (isNeedCheckRecompile) {
            deployDataDatabase.getEffectedSourceAndClass(changedMethodRef, changedFieldRef, changedAbstractClasses)
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
            val isLib = it.type == CompileOutput.Type.Asset && it.name.startsWith("lib/")
            return@filter isAndroidManifest || isLib
        }.toMutableList()
        if (updateApkFiles.any { it.name == "AndroidManifest.xml" }) {
            // add resources.arsc too
            val resourcesArsc = overlays.find { it.name == "resources.arsc" }
            if (resourcesArsc != null) {
                updateApkFiles += resourcesArsc
            }
        }

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

    fun getAllInterfacesWithDefaultMethod(classFiles: List<CompileFile>): List<String> {
        val files = classFiles.map { it.file }
        val parser = ClassFileParser(files)
        parser.parse()
        return getAllInterfacesWithDefaultMethod(
            parser.interfaces.toList(), parser.staticInvocationRefs.toList()
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

    private val String.isInnerClass: Boolean
        get() = contains('$')
}
