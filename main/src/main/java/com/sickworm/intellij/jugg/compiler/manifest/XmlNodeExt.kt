package com.sickworm.intellij.jugg.compiler.manifest

import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList

private val uniqueNodeName = setOf(
    "manifest", "application", "uses-sdk", "queries",
)

val Node.uniqueKey: String get() {
    if (nodeName in uniqueNodeName) { // they are unique
        return nodeName
    }

    val name = this["android:name"]
    if (name != null) {
        return "$nodeName:$name"
    }

    // use all child as unique key
    val nameSet = mutableSetOf<String>()
    childNodes.forEach {
        if (it.nodeType != Node.ELEMENT_NODE) {
            return@forEach
        }
        val stringBuilder = StringBuilder()
        stringBuilder.append(it.nodeName)
        stringBuilder.append(":")
        stringBuilder.append("<")
        it.attributes?.forEach { attribute ->
            stringBuilder.append(attribute.nodeName)
            stringBuilder.append(":")
            stringBuilder.append(attribute.nodeValue)
            stringBuilder.append(";")
        }
        stringBuilder.append(">")
        nameSet.add(stringBuilder.toString())
    }
    return nameSet.sorted().joinToString("&")
}

// get attribute value of node
operator fun Node?.get(name: String): String? {
    this ?: return null

    attributes?.forEach {
        if (it.nodeName == name) {
            return it.nodeValue
        }
    }
    return null
}

inline fun NodeList.find(condition: (Node) -> Boolean): Node? {
    forEach {
        if (condition(it)) {
            return it
        }
    }
    return null
}


fun Element.importChildNotDeep(child: Element, isExcludeToolsAttribute: Boolean): Element {
    val importedNode = ownerDocument.importNode(child, false) as Element
    if (isExcludeToolsAttribute) {
        val toolsAttributes = mutableSetOf<Node>()
        importedNode.attributes?.forEach {
            if (it.nodeName.startsWith("tools:")) {
                toolsAttributes.add(it)
            }
        }
        toolsAttributes.forEach {
            importedNode.removeAttribute(it.nodeName)
        }
    }
    return appendChild(importedNode) as Element
}

fun ManifestDiffResult.DiffElement.diffAttributes(oldNode: Element?) {
    addedAttributes.clear()
    updatedAttributes.clear()
    removedAttributes.clear()

    val remainAttributes = mutableMapOf<String, Node>()
    oldNode?.attributes?.forEach {
        remainAttributes[it.nodeName] = it
    }

    node.attributes?.forEach {
        val oldAttribute = remainAttributes.remove(it.nodeName)
        if (oldAttribute == null) {
            addedAttributes.add(it)
        } else if (oldAttribute.nodeValue != it.nodeValue) {
            updatedAttributes.add(it)
        }
    }

    removedAttributes.addAll(remainAttributes.values)
}

inline fun NodeList.forEach(block: (Node) -> Unit) {
    val size = this.length
    for (i in 0 until size) {
        block(this.item(i))
    }
}

inline fun NamedNodeMap.forEach(block: (Node) -> Unit) {
    val size = this.length
    for (i in 0 until size) {
        block(this.item(i))
    }
}