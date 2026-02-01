package com.sickworm.intellij.jugg.compiler.databinding

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.manifest.XmlParser
import com.sickworm.intellij.jugg.compiler.manifest.find
import com.sickworm.intellij.jugg.compiler.manifest.forEach
import com.sickworm.intellij.jugg.logger.getInstance
import org.w3c.dom.Element
import java.io.File

/**
 * Find out all the includes xml files in [DataBindingArgsManager.backupDataBindingLayoutXmlDir] recursively
 * to fix compilation error with DataBindingImpl.java
 */
class LayoutIncludeAnalyzer(
    private val argsManager: DataBindingArgsManager,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("LayoutIncludeAnalyzer")

    fun findAllIncludePath(compileDataBindingXmlFiles: List<CompileFile>): List<File> {
        try {
            // convert to layout info files
            var layoutInfoFiles = mutableListOf<File>()
            compileDataBindingXmlFiles.forEach { file ->
                val subLayoutInfoFiles = findLayoutInfoFileByLayoutName(file.file.nameWithoutExtension, file.file.parentFile.name)
                if (subLayoutInfoFiles.isEmpty()) {
                    logger.warn("Can not find layout info file for layout file: $file")
                    return@forEach
                }
                layoutInfoFiles.addAll(subLayoutInfoFiles)
            }

            // find the include layout info files recursively
            val result = mutableListOf<File>()
            while (layoutInfoFiles.isNotEmpty()) {
                val includeLayoutNames = mutableListOf<String>()
                layoutInfoFiles.forEach { file ->
                    findIncludeLayouts(file, XmlParser().parse(file).node, includeLayoutNames)
                }
                logger.debug("findAllIncludeLayoutNames source: ${layoutInfoFiles.joinToString { it.name }}, result: $includeLayoutNames")

                val newIncludeLayoutInfoFiles = mutableListOf<File>()
                includeLayoutNames.forEach { name ->
                    val subLayoutInfoFiles = findLayoutInfoFileByLayoutName(name)
                    if (subLayoutInfoFiles.isEmpty()) {
                        logger.warn("Can not find layout info file for layout name: $name")
                        return@forEach
                    }
                    subLayoutInfoFiles.forEach { layoutInfoFile ->
                        if (layoutInfoFile !in result) { // avoid dead loop
                            newIncludeLayoutInfoFiles.add(layoutInfoFile)
                        }
                    }
                }
                logger.debug("found includedLayoutNames: $includeLayoutNames" +
                        ", newIncludedLayoutNames: $newIncludeLayoutInfoFiles")
                result.addAll(newIncludeLayoutInfoFiles)
                layoutInfoFiles = newIncludeLayoutInfoFiles
            }

            return result
        } catch (e: Throwable) {
            logger.debug("findAllIncludePath failed", e)
            logger.warn("findAllIncludePath failed $e")
            return emptyList()
        }
    }

    private fun findIncludeLayouts(layoutInfoFile: File, node: Element, result: MutableList<String>) {
        val targetNodes = node
            .childNodes.find { it is Element && it.tagName == "Targets" }
            ?.childNodes

        if (targetNodes == null) {
            logger.warn("$layoutInfoFile has no \"Targets\" node, databinding may have compat issue, please report to admin.")
            logger.debug("content: ${layoutInfoFile.readText()}")
            return
        }

        targetNodes.forEach { targetNode ->
            if (targetNode !is Element) {
                return@forEach
            }
            val includeLayout = targetNode.getAttribute("include")
            if (includeLayout.isNotEmpty()) {
                logger.debug("found include layout $includeLayout in $layoutInfoFile")
                result.add(includeLayout)
            }
        }
    }

    /**
     * find the layout info file by layout name in [DataBindingArgsManager.backupDataBindingLayoutXmlDir] and
     */
    private fun findLayoutInfoFileByLayoutName(layoutName: String, parentFileName: String? = null): List<File> {
        // find in moduleInfo
        val finalLayoutName = layoutName + if (parentFileName != null) "-$parentFileName.xml" else "-"
        val layoutInfoFiles = argsManager.backupDataBindingLayoutXmlDir.listFiles()?.filter {
            it.name.startsWith(finalLayoutName)
        }
        if (!layoutInfoFiles.isNullOrEmpty()) {
            return layoutInfoFiles
        }

        // find in dependency modules
        logger.debug("can not find layout info file for $finalLayoutName in moduleInfo, finds in dependency modules")
        val dependentModules = argsManager.moduleInfo.moduleDependencies
        dependentModules.forEach { dependantModule ->
            val subModuleInfo = argsManager.context.modules[dependantModule.moduleName] ?: run {
                logger.debug("can not find module ${dependantModule.moduleName} in moduleInfo, skips")
                return@forEach
            }
            val subArgsManager = DataBindingArgsManager(argsManager.context, subModuleInfo)
            val layoutXmlDir = if (subArgsManager.backupDataBindingLayoutXmlDir.exists()) {
                subArgsManager.backupDataBindingLayoutXmlDir
            } else {
                subArgsManager.gradleDataBindingLayoutXmlDir
            }
            val subLayoutInfoFile = layoutXmlDir.listFiles()?.filter {
                it.name.startsWith(finalLayoutName)
            }
            if (!subLayoutInfoFile.isNullOrEmpty()) {
                return subLayoutInfoFile
            }
        }
        logger.debug("can not find layout info file for $layoutName in dependency modules")
        return emptyList()
    }

}