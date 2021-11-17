package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.*
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

class DeployDataDb(
    private val compileContextManager: CompileContextManager,
    private val logger: Logger,
) {

    private var deployedClasses: MutableMap<String, JuggFileInfo> = mutableMapOf()
    private var deployedOverlays: MutableMap<String, JuggFileInfo> = mutableMapOf()

    @Synchronized
    fun buildDeployData(items: Collection<DeployItem>): JuggDeployData {
        val apks = compileContextManager.compileContext.apkInfos

        val changedClasses = items.filter { it.type == CompileOutput.Type.Dex }
        val changedOverlays = items.filter { it.type == CompileOutput.Type.Overlay }

        val newClasses = changedClasses.filter {
            isNewClass(it.path)
        }
        val modifiedClasses = changedClasses - newClasses

        val overlays = changedOverlays.toMutableList()
        if (changedOverlays.isNotEmpty() && deployedOverlays.isEmpty()) {
            // first time deploy must do full deployment
            logger.debug("first time deploy overlay, need full deployment")

            val nameSet = overlays.map { it.path }.toSet()
            val costTime = measureTimeMillis {
                val parsedApks = compileContextManager.compileContext.parsedApks
                parsedApks.forEach { parsedApk ->
                    parsedApk.overlayFiles.forEach loop@{
                        val path = it.value.name
                        if (nameSet.contains(path)) return@loop
                        val deployItem = DeployItem(
                            path = path,
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

        return JuggDeployData(apks, newClasses, modifiedClasses, overlays)
    }

    private fun readFileContentFromApk(apk: File, path: String): ByteArray {
        val zipFile = ZipFile(apk)
        val entry = zipFile.getEntry(path) ?: throw JuggInternalException.apkEntryNotFound(apk, path)
        val inputStream = zipFile.getInputStream(entry)
        return inputStream.readAllBytes()
    }

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

    @Synchronized
    fun update(overlayUpdate: JuggDeployData) {
        overlayUpdate.newClasses.forEach {
            deployedClasses[it.path] = JuggFileInfo(it.path, it.checksum)
        }
        overlayUpdate.modifiedClasses.forEach {
            deployedClasses[it.path] = JuggFileInfo(it.path, it.checksum)
        }
        overlayUpdate.overlays.forEach {
            deployedOverlays[it.path] = JuggFileInfo(it.path, it.checksum)
        }
    }
}