package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.ParsedApk
import com.sickworm.intellij.jugg.apk.ApkParser
import com.sickworm.intellij.jugg.apk.JuggFileInfo
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

/**
 * Generate [JuggDeployData] according to deployment history.
 */
class DeployDataGenerator(
    private val logger: Logger,
) {

    private var parsedApks: List<ParsedApk> = emptyList()
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

        val newClasses = changedClasses.filter {
            isNewClass(it.name)
        }
        val modifiedClasses = changedClasses - newClasses.toSet()
        logger.debug("newClasses: $newClasses")

        val hotReloadModifiedClasses = modifiedClasses.filter {
            isHotReloadClass(it.name, it.classNode)
        }
        logger.debug("hotReloadModifiedClasses: $hotReloadModifiedClasses")

        val hotFixModifiedClasses = modifiedClasses - hotReloadModifiedClasses.toSet()
        logger.debug("hotFixModifiedClasses: $hotFixModifiedClasses")

        val changedOverlays = items.filter { it.type == CompileOutput.Type.Overlay }
        val overlays = changedOverlays.toMutableList()
        var isFullOverlays = false
        if (changedOverlays.isNotEmpty() && deployedOverlays.isEmpty()) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")
            isFullOverlays = true

            val nameSet = overlays.map { it.name }.toSet()
            val costTime = measureTimeMillis {
                parsedApks.forEach { parsedApk ->
                    parsedApk.overlayFiles.forEach loop@{
                        val path = it.value.name
                        if (nameSet.contains(path)) return@loop
                        val deployItem = DeployItem(
                            name = path,
                            type = CompileOutput.Type.Overlay,
                            checksum = it.value.checksum,
                            content = readFileContentFromApk(parsedApk.apkInfo.files.first().apkFile, path)
                        )
                        overlays.add(deployItem)
                    }
                }
            }

            logger.debug("first time deploy overlay, need full deployment finish, cost ${costTime}ms")
        }

        val apks = parsedApks.map { it.apkInfo }
        return JuggDeployData(apks, newClasses, hotFixModifiedClasses, hotReloadModifiedClasses, overlays, isFullOverlays, isWarmUp)
    }

    private fun readFileContentFromApk(apk: File, path: String): ByteArray {
        val zipFile = ZipFile(apk)
        val entry = zipFile.getEntry(path) ?: throw JuggInternalException.apkEntryNotFound(apk, path)
        val inputStream = zipFile.getInputStream(entry)
        return inputStream.readAllBytes()
    }

    /**
     * check whether the class has deployment before
     */
    private fun isNewClass(className: String): Boolean {
        if (deployedClasses.containsKey(className)) {
            return false
        }

        if (parsedApks.any { it.containsClass(className) }) {
            return false
        }

        return true
    }

    private fun isHotReloadClass(className: String, newClassNode: ClassNode): Boolean {
        var oldClassNode: ClassNode? = deployedClasses[className]
        if (oldClassNode == null) {
            oldClassNode = parsedApks.firstNotNullOfOrNull {
                it.getClass(className)
            }
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
        val costTime = measureTimeMillis {
            parsedApks = apks.map {
                ApkParser().parse(it)
            }
            deployedClasses.clear()
            deployedOverlays.clear()
        }

        deployedItems.forEach {
            if (it.type == CompileOutput.Type.Dex) {
                deployedClasses[it.name] = ApkParser().parseDex(it.content).first().value
            } else {
                deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
            }
        }

        logger.debug("init finish, cost ${costTime}ms. load " +
            "classes ${parsedApks.sumOf { it.getClassSize() }}, " +
            "overlays ${parsedApks.sumOf { it.overlayFiles.size }}, " +
            "deployedClasses ${deployedClasses.size}, " +
            "deployedOverlays ${deployedOverlays.size}"
        )

        // TODO reopen
        // close for now for better performance and compile consistency
//        // something wrong with this... use build class path for now
//        parsedApks.forEach { apk ->
//            apk.classes.values.forEach { classNode ->
//                val bytes = classNode.dumpClassStub()
//                val outputPath = classNode.className.replace('.', '/') + ".class"
//                val outputFile = File(fullBuildClassPathDir, outputPath)
//                if (outputFile.exists()) {
//                    outputFile.delete()
//                }
//                outputFile.parentFile?.mkdirs()
//                outputFile.writeBytes(bytes)
//            }
//        }
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