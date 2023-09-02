package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Generate [JuggDeployData] according to deployment history.
 */
class DeployDataGenerator(
    private val logger: Logger,
    databaseDir: File,
) {

    private var deployDataDatabase: IDeployDataDatabase = DeployDataDatabase(File(databaseDir, "apk"), logger)

    /**
     * Build [JuggDeployData] according to deployment history.
     */
    @Synchronized
    fun buildDeployData(items: List<DeployItem>, isWarmUp: Boolean): JuggDeployData {
        val changedDex = items.filter { it.type == CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(changedDex)
        val changedClasses = parsedDex.classDeployItems

        val oldClassNodes = deployDataDatabase.getClassNodes(changedClasses.map { it.name })
        val newClasses = mutableListOf<ClassDeployItem>()
        val hotReloadModifiedClasses = mutableListOf<ClassDeployItem>()
        val hotFixModifiedClasses = mutableListOf<ClassDeployItem>()
        val changedMethodRef = mutableListOf<MethodNode>()
        val changedFieldRef = mutableListOf<FieldNode>()
        changedClasses.forEach {
            val className = it.name
            val isNewClass = oldClassNodes.containsKey(className)
            if (!isNewClass) {
                newClasses.add(it)
                return@forEach
            }
            val oldClassNode: ClassNode? = oldClassNodes[it.name]
            if (oldClassNode == null) {
                // this should not happen, because we just run [isNewClass]
                logger.warn("class $className not found, ignore.")
                return@forEach
            }

            // compare class node difference
            val newClassNode = it.classNode
            val result = ClassNodeComparator(oldClassNode, newClassNode).compare()
            logger.debug(result.toString())
            if (result.isSameStructure) {
                // same structure, hot reload
                hotReloadModifiedClasses.add(it)
            } else {
                // different structure, hot fix
                logger.debug("class $className structure changed, need hot fix: $result")
                hotFixModifiedClasses.add(it)
            }

            changedMethodRef.addAll(result.deletedMethods)
            changedFieldRef.addAll(result.deletedFields)
        }
        logger.debug("newClasses: $newClasses")
        logger.debug("hotReloadModifiedClasses: $hotReloadModifiedClasses")
        logger.debug("hotFixModifiedClasses: $hotFixModifiedClasses")

        val changedOverlays = items.filter { it.type == CompileOutput.Type.Overlay }
        var overlays = changedOverlays
        var isFullOverlays = false
        if (changedOverlays.isNotEmpty() && !deployDataDatabase.isDeployedOverlaysBefore()) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")
            isFullOverlays = true
            val costTime = measureTimeMillis {
                overlays = deployDataDatabase.getFullOverlays(overlays)
            }

            logger.debug("first time deploy overlay, need full deployment finish, cost ${costTime}ms")
        }

        val effectedSourceAndClassNodes = deployDataDatabase.getEffectedSourceAndClass(changedMethodRef, changedFieldRef)
        if (effectedSourceAndClassNodes.isNotEmpty()) {
            logger.debug("found effected source and classes: $effectedSourceAndClassNodes")
        }

        val apks = deployDataDatabase.getApkInfos()
        val juggDeployData = JuggDeployData(apks,
            newClasses, hotFixModifiedClasses, hotReloadModifiedClasses,
            effectedSourceAndClassNodes.keys.toList(),
            overlays, parsedDex,
            isFullOverlays, isWarmUp,
        )
        logger.debug("juggDeployData: $juggDeployData")
        return juggDeployData
    }

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>) {
        deployDataDatabase.init(apks, deployedItems)
    }

    /**
     * Mark [juggDeployData] as deployed.
     */
    @Synchronized
    fun commitDeployedData(juggDeployData: JuggDeployData) {
        deployDataDatabase.commitDeployedData(juggDeployData)
    }
}