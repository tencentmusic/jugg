package com.sickworm.intellij.jugg.compiler.manifest

import com.android.utils.forEach
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


class ManifestDiffer {

    fun diff(changedManifestFile: ChangedManifestFile): ManifestDiffResult {
        val newNode = XmlParser().parse(changedManifestFile.newFile)
        val oldNode = changedManifestFile.oldFile?.let { XmlParser().parse(it) }
        val diffElement = diff(newNode, oldNode)
        return ManifestDiffResult(changedManifestFile, diffElement)
    }

    fun diff(newNode: XmlNode, oldNode: XmlNode?): ManifestDiffResult.DiffElement {
        preprocess(newNode)
        if (oldNode != null) {
            preprocess(oldNode)
        }

        val builderFactory = DocumentBuilderFactory.newInstance()
        val doc = builderFactory.newDocumentBuilder().newDocument()

        val holderNode = ManifestDiffResult.DiffElement(doc.createElement("holder"), true)
        diffNode(holderNode, newNode.node, oldNode?.node)

        if (holderNode.isNothingToUpdate) {
            // return empty node
            return ManifestDiffResult.DiffElement(doc.createElement(MANIFEST_TAG_NAME), false)
        }
        return holderNode.changedChildren.first()
    }

    private fun diffNode(parentDiffElement: ManifestDiffResult.DiffElement, newNode: Element, oldNode: Element?) {
        val isNewNode = oldNode == null
        val currentDiffElement = ManifestDiffResult.DiffElement(newNode, isNewNode)
        currentDiffElement.diffAttributes(oldNode)

        val nodeMatcher = ManifestNodeMatcher(newNode.childNodes, oldNode?.childNodes)
        newNode.childNodes.forEach { newChildNode ->
            if (newChildNode.nodeType != Node.ELEMENT_NODE) {
                // ignore text node etc.
                return@forEach
            }

            if (newChildNode.nodeName == "uses-sdk") {
                // don't merge uses-sdk, because I think it's do more harm than good
                return@forEach
            }

            // check recursively
            val relativeNode: Node? = nodeMatcher.findRelativeChild(newChildNode)
            diffNode(currentDiffElement, newChildNode as Element, relativeNode as? Element)
        }

        if (isNewNode) {
            parentDiffElement.changedChildren.add(currentDiffElement)
        } else if (currentDiffElement.changedAttributes.isNotEmpty()) {
            parentDiffElement.changedChildren.add(currentDiffElement)
        } else if (currentDiffElement.changedChildren.isNotEmpty()) {
            // child is not the same, keep current node
            parentDiffElement.changedChildren.add(currentDiffElement)
        } else {
            // total equals, won't add to changedChildren
        }
    }

    private fun preprocess(node: XmlNode) {
        val packageName = node.node["package"] ?: throw IllegalStateException("package name is required in AndroidManifest.xml")
        preprocess(node.node, packageName)
    }

    private fun preprocess(node: Node, packageName: String) {
        node.attributes?.forEach {
            if (it.nodeName == "android:name") {
                val name = it.nodeValue
                if (name != null && name.startsWith(".")) {
                    it.nodeValue = packageName + name
                    return
                }
            }
        }

        node.childNodes.forEach {
            preprocess(it, packageName)
        }
    }

    companion object {
        private const val MANIFEST_TAG_NAME = "manifest"
    }
}

class ManifestNodeMatcher(
    private val newNodes: NodeList,
    private val oldNodes: NodeList?,
) {

    private val matchPair = mutableMapOf<Node, Node?>()

    init {
        init()
    }

    private fun init() {
        // oldNodes is null, just leave matchPair empty
        if (oldNodes == null) {
            return
        }

        matchPair.clear()

        val oldNodeWithDeclareNameMap = mutableMapOf<String, Element>()
        oldNodes.forEach {
            if (it.nodeType != Node.ELEMENT_NODE) {
                return@forEach
            }

            val declaredName = it.uniqueKey
            oldNodeWithDeclareNameMap[declaredName] = it as Element
        }

        newNodes.forEach { newNode ->
            if (newNode.nodeType != Node.ELEMENT_NODE) {
                return@forEach
            }

            val relativeOldNode = oldNodeWithDeclareNameMap[newNode.uniqueKey]
            matchPair[newNode] = relativeOldNode
        }
    }

    fun findRelativeChild(node: Node): Node? {
        return matchPair[node]
    }
}

data class ChangedManifestFile(
    val newFile: File,
    val oldFile: File?,
)

class ManifestDiffResult(
    private val changedManifestFile: ChangedManifestFile,
    val diffElement: DiffElement,
) {

    private val newFile get() = changedManifestFile.newFile
    private val oldFile get() = changedManifestFile.oldFile

    override fun toString(): String {
        return "ChangedManifestFile(" +
                "newFile=$newFile, exists: ${newFile.exists()}; " +
                "oldFile=$oldFile, exists: ${oldFile?.exists()}; " +
                "diffElement=${diffElement.toXmlString()}, isEmpty: ${diffElement.isNothingToUpdate}"
    }

    class DiffElement(
        val node: Element,
        val isNewNode: Boolean,
        val addedAttributes: MutableList<Node> = mutableListOf(),
        val updatedAttributes: MutableList<Node> = mutableListOf(),
        val removedAttributes: MutableList<Node> = mutableListOf(),
        val changedChildren: MutableList<DiffElement> = mutableListOf(),
    ) {

        val changedAttributes get() = addedAttributes + updatedAttributes

        val isNothingToUpdate: Boolean
            get() = addedAttributes.isEmpty() && updatedAttributes.isEmpty() && changedChildren.isEmpty()

        fun toXmlString(stringBuilder: StringBuilder = StringBuilder(), indentLevel: Int = 0): String {
            val indentNum = 2
            stringBuilder.append(" ".repeat(indentLevel * indentNum))
            stringBuilder.append("<${node.nodeName}")
            addedAttributes.forEach {
                stringBuilder.append(" ${it.nodeName}=\"${it.nodeValue}\"")
            }
            updatedAttributes.forEach {
                stringBuilder.append(" ${it.nodeName}=\"${it.nodeValue}\"")
            }
            stringBuilder.append(">")
            stringBuilder.append("\n")
            changedChildren.forEach {
                it.toXmlString(stringBuilder, indentLevel + 1)
            }
            stringBuilder.append(" ".repeat(indentLevel * indentNum))
            stringBuilder.append("</${node.nodeName}>")
            stringBuilder.append("\n")

            return stringBuilder.toString()
        }
    }
}
