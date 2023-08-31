package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Generate [JuggDeployData] according to deployment history.
 */
class DeployDataGenerator(
    private val logger: Logger,
    databaseDir: File,
) {

    private var parsedApkDatabase: IParsedApkDatabase = ParsedApkDatabase(File(databaseDir, "apk"), logger)
    private var deployedClasses: MutableMap<String, ClassNode> = mutableMapOf()
    private var deployedOverlays: MutableMap<String, JuggFileInfo> = mutableMapOf()

    /**
     * Build [JuggDeployData] according to deployment history.
     */
    @Synchronized
    fun buildDeployData(items: Collection<DeployItem>, isWarmUp: Boolean): JuggDeployData {
        val changedClasses = items
            .filter {
                it.type == CompileOutput.Type.Dex
            }
            .map {
                val dexClassNodes = ApkParser().parseDex(it.content)
                if (dexClassNodes.size != 1) {
                    // it must be only one class in one dex
                    throw JuggInternalException.dexFileNotContainsOnlyOneClass(dexClassNodes.size)
                }
                val dexClassNode = dexClassNodes.first().value
                ClassDeployItem(it, dexClassNode)
            }

        val oldClassNodes = parsedApkDatabase.getClassNodes(changedClasses.map { it.name })
        val newClasses = changedClasses.filter {
            isNewClass(it.name, oldClassNodes)
        }
        val modifiedClasses = changedClasses - newClasses.toSet()
        logger.debug("newClasses: $newClasses")

        val hotReloadModifiedClasses = modifiedClasses.filter {
            isHotReloadClass(it.name, it.classNode, oldClassNodes)
        }
        logger.debug("hotReloadModifiedClasses: $hotReloadModifiedClasses")

        val hotFixModifiedClasses = modifiedClasses - hotReloadModifiedClasses.toSet()
        logger.debug("hotFixModifiedClasses: $hotFixModifiedClasses")

        val changedOverlays = items.filter { it.type == CompileOutput.Type.Overlay }
        var overlays = changedOverlays
        var isFullOverlays = false
        if (changedOverlays.isNotEmpty() && deployedOverlays.isEmpty()) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")
            isFullOverlays = true
            val costTime = measureTimeMillis {
                overlays = parsedApkDatabase.getFullOverlays(overlays)
            }

            logger.debug("first time deploy overlay, need full deployment finish, cost ${costTime}ms")
        }

        val apks = parsedApkDatabase.getApkInfos()
        return JuggDeployData(apks, newClasses, hotFixModifiedClasses, hotReloadModifiedClasses, overlays, isFullOverlays, isWarmUp)
    }

    /**
     * check whether the class has deployment before
     */
    private fun isNewClass(className: String, oldClassNodes: Map<String, ClassNode>): Boolean {
        if (deployedClasses.containsKey(className)) {
            return false
        }

        return oldClassNodes.containsKey(className)
    }

    private fun isHotReloadClass(className: String, newClassNode: ClassNode, oldClassNodes: Map<String, ClassNode>): Boolean {
        var oldClassNode: ClassNode? = deployedClasses[className]
        if (oldClassNode == null) {
            oldClassNode = oldClassNodes[className]
        }
        if (oldClassNode == null) {
            // this should not happen, because we just run [isNewClass]
            logger.warn("class $className not found, ignore.")
            return false
        }

        // compare class node difference
        val result = ClassNodeComparator(oldClassNode, newClassNode).compare()
        logger.debug(result.toString())

        if (!result.isSameStructure) {
            logger.debug("class $className structure changed, need hot fix: $result")
        }

        return result.isSameStructure
    }

    /**
     * 1. Collect information after compiled
     * 2. add deployed items to [deployedClasses] and [deployedOverlays] (invokes when recover on project opened)
     */
    @Synchronized
    fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>) {
        logger.debug("initAfterInstall parsed apk start, apks: $apks")
        parsedApkDatabase.init(apks)
        deployedClasses.clear()
        deployedOverlays.clear()

        deployedItems.forEach {
            if (it.type == CompileOutput.Type.Dex) {
                deployedClasses[it.name] = ApkParser().parseDex(it.content).first().value
            } else {
                deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
            }
        }
    }

    /**
     * Mark [juggDeployData] as deployed.
     */
    @Synchronized
    fun commitDeployedData(juggDeployData: JuggDeployData) {
        juggDeployData.classes.forEach {
            deployedClasses[it.name] = it.classNode
        }
        juggDeployData.overlays.forEach {
            deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
        }
    }
}