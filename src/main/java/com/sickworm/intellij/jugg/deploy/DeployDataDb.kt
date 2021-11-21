package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.*
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.DexClassNodeWrapper
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

class DeployDataDb(
    private val compileContextManager: CompileContextManager,
    private val logger: Logger,
) {

    // TODO persist
    private var deployedClasses: MutableMap<String, DexClassNodeWrapper> = mutableMapOf()
    private var deployedOverlays: MutableMap<String, JuggFileInfo> = mutableMapOf()

    @Synchronized
    fun buildDeployData(items: Collection<DeployItem>): JuggDeployData {
        val apks = compileContextManager.compileContext.apkInfos

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
        val modifiedClasses = changedClasses - newClasses

        val hotReloadModifiedClasses = modifiedClasses.filter {
            isHotReloadClass(it.name, it.dexClassNode)
        }
        val hotFixModifiedClasses = modifiedClasses - hotReloadModifiedClasses

        val changedOverlays = items.filter { it.type == CompileOutput.Type.Overlay }
        val overlays = changedOverlays.toMutableList()
        if (changedOverlays.isNotEmpty() && deployedOverlays.isEmpty()) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")

            val nameSet = overlays.map { it.name }.toSet()
            val costTime = measureTimeMillis {
                val parsedApks = compileContextManager.compileContext.parsedApks
                parsedApks.forEach { parsedApk ->
                    parsedApk.overlayFiles.forEach loop@{
                        val path = it.value.name
                        if (nameSet.contains(path)) return@loop
                        val deployItem = DeployItem(
                            name = path,
                            type = CompileOutput.Type.Overlay,
                            checksum = it.value.checksum,
                            content = readFileContentFromApk(parsedApk.apkInfo.file, path)
                        )
                        overlays.add(deployItem)
                    }
                }
            }

            logger.debug("first time deploy overlay, need full deployment finish, cost $costTime")
        }

        return JuggDeployData(apks, newClasses, hotFixModifiedClasses, hotReloadModifiedClasses, overlays)
    }

    private fun readFileContentFromApk(apk: File, path: String): ByteArray {
        val zipFile = ZipFile(apk)
        val entry = zipFile.getEntry(path) ?: throw JuggInternalException.apkEntryNotFound(apk, path)
        val inputStream = zipFile.getInputStream(entry)
        return inputStream.readAllBytes()
    }

    /**
     * check whether the class has deploy before
     */
    private fun isNewClass(className: String): Boolean {
        if (deployedClasses.containsKey(className)) {
            return false
        }

        val apks = compileContextManager.compileContext.parsedApks
        if (apks.any { it.classes.containsKey(className) }) {
            return false
        }

        return true
    }

    private fun isHotReloadClass(className: String, newDexClassNode: DexClassNodeWrapper): Boolean {
        val apks = compileContextManager.compileContext.parsedApks

        var oldDexClassNode: DexClassNodeWrapper? = deployedClasses[className]
        if (oldDexClassNode == null) {
            oldDexClassNode = apks.firstNotNullResult {
                it.classes[className]
            }
        }
        if (oldDexClassNode == null) {
            // this should not happened, because we just run [isNewClass]
            return false
        }

        // compare class node difference
        val result = ClassNodeComparator(oldDexClassNode, newDexClassNode).compare()
        return result.isSameStructure
    }

    @Synchronized
    fun update(overlayUpdate: JuggDeployData) {
        overlayUpdate.classes.forEach {
            deployedClasses[it.name] = it.dexClassNode
        }
        overlayUpdate.overlays.forEach {
            deployedOverlays[it.name] = JuggFileInfo(it.name, it.checksum)
        }
    }
}