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
    fun buildDeployData(items: List<DeployItem>, isWarmUp: Boolean = false): JuggDeployData {
        val changedDex = items.filter { it.type == CompileOutput.Type.Dex }
        val parsedDex = ApkParser().parseDex(changedDex)
        val changedOverlays = items.filter { it.type == CompileOutput.Type.Overlay }
        return buildDeployData(parsedDex, changedOverlays, isWarmUp)
    }

    @TestOnly
    fun buildDeployData(parsedDex: ParsedDex, changedOverlays: List<DeployItem>, isWarmUp: Boolean = false): JuggDeployData {
        val startTime = System.currentTimeMillis()

        val changedClasses = parsedDex.classDeployItems
        val oldClassNodes = deployDataDatabase.getClassNodes(changedClasses.map { it.sigName })
        val newClasses = mutableListOf<ClassDeployItem>()
        val hotReloadModifiedClasses = mutableListOf<ClassDeployItem>()
        val hotFixModifiedClasses = mutableListOf<ClassDeployItem>()
        val changedMethodRef = mutableListOf<MethodNode>()
        val changedFieldRef = mutableListOf<FieldNode>()
        changedClasses.forEach {
            val className = it.sigName
            val oldClassNode: ClassNode? = oldClassNodes[className]
            if (oldClassNode == null) {
                newClasses.add(it)
                return@forEach
            }

            // compare class node difference
            val newClassNode = it.classNode
            val result = ClassNodeComparator(oldClassNode, newClassNode).compare()
            if (result.isSameStructure) {
                // same structure, hot reload
                logger.debug("class $className structure not changed: $result")
                hotReloadModifiedClasses.add(it)
            } else {
                // different structure, hot fix
                logger.debug("class $className structure changed, need hot fix: $result")
                hotFixModifiedClasses.add(it)
            }

            // we don't care about abstract, because it won't affect class bytecode.
            // ignore abstract can stop recompile when redex interface class default method (which will make methods be not abstract)
            changedMethodRef.addAll(result.deletedMethodsExceptAbstract)
            changedFieldRef.addAll(result.deletedFields)
        }

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

        val includeClassNames = changedClasses.map { it.sigName }.toSet()
        val effectedSourceAndClassNodes = deployDataDatabase.getEffectedSourceAndClass(includeClassNames, changedMethodRef, changedFieldRef)
        if (effectedSourceAndClassNodes.isNotEmpty()) {
            logger.debug("effected source and class nodes: $effectedSourceAndClassNodes")
        }
        val interfacesWithDesugaredDefaultMethod = deployDataDatabase.findInterfacesWithDesugaredDefaultMethod(changedClasses.map { it.classNode })
        if (interfacesWithDesugaredDefaultMethod.isNotEmpty()) {
            logger.debug("interfaces with desugared default method: $interfacesWithDesugaredDefaultMethod")
        }

        val apks = deployDataDatabase.getApkInfos()
        val juggDeployData = JuggDeployData(apks,
            newClasses, hotFixModifiedClasses, hotReloadModifiedClasses,
            effectedSourceAndClassNodes.keys.toList(),
            interfacesWithDesugaredDefaultMethod,
            overlays, parsedDex,
            isFullOverlays, isWarmUp,
        )

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("buildDeployData finish, cost ${costTime}ms.")
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
