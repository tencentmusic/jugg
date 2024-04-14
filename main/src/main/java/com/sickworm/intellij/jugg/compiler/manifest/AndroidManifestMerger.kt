package com.sickworm.intellij.jugg.compiler.manifest

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
class AndroidManifestMerger {

    /**
     * @throws Exception if merge failed
     */
    fun merge(mergedManifestFile: File, changedManifestFiles: List<ChangedManifestFile>, outputFile: File) {
        val fullNode = XmlParser().parse(mergedManifestFile)
        val diffElements = changedManifestFiles.map {
            val diffResult = ManifestDiffer().diff(it)
            diffResult.diffElement
        }
        merge(fullNode, diffElements)

        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(fullNode.printXml())
    }

    fun merge(fullNode: XmlNode, diffElements: List<ManifestDiffResult.DiffElement>) {
        diffElements.forEach { diffElement ->
            merge(fullNode.node, diffElement)
        }
    }

    private fun merge(fullNode: Element, diffElement: ManifestDiffResult.DiffElement) {
        diffElement.changedAttributes.forEach {
            fullNode.setAttribute(it.nodeName, it.nodeValue)
        }
        val nodeMatcher = ManifestNodeMatcher(diffElement.node.childNodes, fullNode.childNodes)
        diffElement.changedChildren.forEach { diffChildNode ->
            val relativeNode = nodeMatcher.findRelativeChild(diffChildNode.node)
            if (relativeNode == null) {
                if (diffChildNode.isNewNode) {
                    // we only add it into fullNode when it is a new node
                    val newNode = fullNode.importChildNotDeep(diffChildNode.node)
                    merge(newNode as Element, diffChildNode)
                }
            } else if (relativeNode.nodeType == Node.ELEMENT_NODE) {
                merge(relativeNode as Element, diffChildNode)
            }
        }
    }
}

