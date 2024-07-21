package com.sickworm.intellij.jugg.compiler.manifest

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File

/**
 * Incremental merge AndroidManifest.xml
 * Diff the manifest changes and then merge into the merged manifest
 *
 * Q: Why don't use standard merge way?
 * A: Three reasons:
 * 1. unable to translate placeholder which is defined in build.gradle
 * 2. unable to get variant manifest e.g. debug/AndroidManifest.xml developmentFree/AndroidManifest.xml
 * 3. we use merged manifest to generate the result, instead of raw manifest
 *
 * Q: Why need diff the changes?
 * A: Merged manifest is the final result, which the tools:remove and tools:replace will be lost.
 * So we need to diff the changes and see that is really added (removed will be ignored for robustly compile result).
 *
 * Standard merge way: [com.android.manifmerger.ManifestMerger2]
 */
class AndroidManifestMerger(loggerArg: Logger) {

    private val logger = loggerArg.getInstance("AndroidManifestMerger")

    /**
     * @return true if changes, false if no changes
     * @throws Exception if merge failed
     */
    fun merge(mergedManifestFile: File, changedManifestFiles: List<ChangedManifestFile>, outputFile: File): Boolean {
        val fullNode = XmlParser().parse(mergedManifestFile)
        val diffElements = changedManifestFiles.map {
            val diffResult = ManifestDiffer().diff(it)
            logger.debug("Diff result: $diffResult")
            diffResult.diffElement
        }
        if (diffElements.all { it.isNothingToUpdate }) {
            return false
        }
        merge(fullNode, diffElements)

        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(fullNode.printXml())
        return true
    }

    fun merge(fullNode: XmlNode, diffElements: List<ManifestDiffResult.DiffElement>) {
        logger.debug("start merge")
        diffElements.forEach { diffElement ->
            merge(fullNode.node, diffElement)
        }
        logger.debug("finish merge")
    }

    private fun merge(fullNode: Element, diffElement: ManifestDiffResult.DiffElement) {
        diffElement.changedAttributes.forEach {
            if (it.nodeName.startsWith("tools:")) {
                logger.debug("ignore tools attribute \"${it.nodeName}\"")
                return@forEach
            }
            if (fullNode.nodeName == "manifest" && it.nodeName == "package") {
                logger.debug("ignore package name update (${it.nodeValue})")
                return@forEach
            }
            if (fullNode.nodeName == "application" && it.nodeName == "android:name") {
                logger.debug("ignore application name update (${it.nodeValue})")
                return@forEach
            }
            fullNode.setAttribute(it.nodeName, it.nodeValue)
            logger.debug("update attribute \"${it.nodeName}\"=\"${it.nodeValue}\" for node <${fullNode.uniqueKey}>")
        }
        val nodeMatcher = ManifestNodeMatcher(diffElement.node.childNodes, fullNode.childNodes)
        diffElement.changedChildren.forEach { diffChildNode ->
            val relativeNode = nodeMatcher.findRelativeChild(diffChildNode.node)
            if (relativeNode == null) {
                if (diffChildNode.isNewNode) {
                    // we only add it into fullNode when it is a new node
                    val newNode = fullNode.importChildNotDeep(diffChildNode.node, isExcludeToolsAttribute = true)
                    logger.debug("insert new node ${newNode.uniqueKey} for node <${fullNode.uniqueKey}>")
                    merge(newNode, diffChildNode)
                }
            } else if (relativeNode.nodeType == Node.ELEMENT_NODE) {
                merge(relativeNode as Element, diffChildNode)
            }
        }
    }
}

