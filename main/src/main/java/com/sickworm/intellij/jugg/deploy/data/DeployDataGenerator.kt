package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ClassNode
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
        val parsedDex = ApkParser().parseDex(items)
        val changedClasses = parsedDex.classDeployItems

        val oldClassNodes = deployDataDatabase.getClassNodes(changedClasses.map { it.name })
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
        if (changedOverlays.isNotEmpty() && !deployDataDatabase.isDeployedOverlaysBefore()) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")
            isFullOverlays = true
            val costTime = measureTimeMillis {
                overlays = deployDataDatabase.getFullOverlays(overlays)
            }

            logger.debug("first time deploy overlay, need full deployment finish, cost ${costTime}ms")
        }

        val apks = deployDataDatabase.getApkInfos()
        return JuggDeployData(apks,
            newClasses, hotFixModifiedClasses, hotReloadModifiedClasses,
            overlays, parsedDex,
            isFullOverlays, isWarmUp,
        )
    }

    /**
     * check whether the class has deployment before
     */
    private fun isNewClass(className: String, oldClassNodes: Map<String, ClassNode>): Boolean {
        return oldClassNodes.containsKey(className)
    }

    private fun isHotReloadClass(className: String, newClassNode: ClassNode, oldClassNodes: Map<String, ClassNode>): Boolean {
        val oldClassNode: ClassNode? = oldClassNodes[className]
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