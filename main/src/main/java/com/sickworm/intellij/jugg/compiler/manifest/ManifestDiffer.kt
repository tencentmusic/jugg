package com.sickworm.intellij.jugg.compiler.manifest

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


class ManifestDiffer {

    fun diff(changedManifestFile: ChangedManifestFile): ManifestDiffResult {
        val newNode = XmlParser().parse(changedManifestFile.newFile)
        val oldNode = changedManifestFile.oldFile?.let { XmlParser().parse(it) }
        val diffElement = diff(newNode, oldNode, changedManifestFile.placeHolders)
        return ManifestDiffResult(changedManifestFile, diffElement)
    }

    fun diff(newNode: XmlNode, oldNode: XmlNode?, placeHolders: Map<String, String>? = null): ManifestDiffResult.DiffElement {
        preprocess(newNode, placeHolders)

        if (oldNode != null) {
            preprocess(oldNode, placeHolders)
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

        // process tools node
        val toolsNode = currentDiffElement.addedAttributes.find { it.nodeName == "tools:node" }
        if (toolsNode != null) {
            if (toolsNode.nodeValue == "remove") {
                return
            }
        }
        currentDiffElement.addedAttributes.removeIf {
            it.nodeName.startsWith("tools:") || it.nodeName == "xmlns:tools"
        }

        // match and diff child node by android:name or something, see Node.uniqueKey
        val nodeMatcher = ManifestNodeMatcher(newNode.childNodes, oldNode?.childNodes)
        newNode.childNodes.forEach { newChildNode ->
            if (newChildNode.nodeType != Node.ELEMENT_NODE) {
                // ignore text node etc.
                return@forEach
            }

            if (newChildNode.nodeName == "uses-sdk") {
                // submodule can also declare uses-sdk, but it's not a good idea to merge them
                // I think it's do more harm than good
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

    /**
     * 1. process android:name=".MainActivity" to "com.example.app.MainActivity"
     * 2. process ${applicationId} to "com.example.app" or something else
     */
    private fun preprocess(node: XmlNode, placeHolders: Map<String, String>?) {
        // namespace and package must exist one of them, and namespace has higher priority, or will get in gradle:
        // Package Name not found in xxx/AndroidManifest.xml, and namespace not specified.
        val packageName = placeHolders?.get(JUGG_NAMESPACE_IN_GRADLE) ?: node.node["package"]
        preprocess(node.node, packageName, placeHolders)
    }

    @Suppress("RegExpRedundantEscape")
    private val regex = "\\$\\{[^}]+\\}".toRegex()

    private fun preprocess(node: Node, packageName: String?, placeHolders: Map<String, String>?) {
        // process
        node.attributes?.forEach {
            if (it.nodeValue == null) {
                return@forEach
            }
            if (it.nodeName == "android:name") {
                val name = it.nodeValue
                if (name != null && name.startsWith(".") && packageName != null) {
                    it.nodeValue = packageName + name
                    return
                }
            }
            if (placeHolders != null) {
                it.nodeValue = regex.replace(it.nodeValue) { matchResult ->
                    val key = matchResult.value.substring(2, matchResult.value.length - 1)
                    return@replace placeHolders[key] ?: matchResult.value
                }
            }
        }

        node.childNodes.forEach {
            preprocess(it, packageName, placeHolders)
        }
    }

    companion object {
        private const val MANIFEST_TAG_NAME = "manifest"
        /**
         * namespace in build.gradle.
         * AndroidManifest.xml will use this as package name if not specified in XML.
         * Jugg read namespace from build.gradle, set it into placeholders for [preprocess].
         */
        const val JUGG_NAMESPACE_IN_GRADLE = "jugg.namespace.gradle"
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
    val placeHolders: Map<String, String>? = null,
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
                "\ndiffElement=isEmpty: ${diffElement.isNothingToUpdate}, content:\n${diffElement.toXmlString()}"
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

        override fun toString(): String {
            return toXmlString()
        }
    }
}
