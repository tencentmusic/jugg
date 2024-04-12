package com.sickworm.intellij.jugg.compiler.manifest

import com.android.utils.forEach
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class XmlParser {

    fun parse(xmlFile: File): XmlNode {
        // parse xml to node
        val builderFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = builderFactory.newDocumentBuilder()
        val doc = docBuilder.parse(xmlFile)
        doc.documentElement.normalize()
        return XmlNode(doc.documentElement)
    }

    fun parse(xmlContent: String): XmlNode {
        // parse xml to node
        val builderFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = builderFactory.newDocumentBuilder()
        val doc = docBuilder.parse(InputSource(StringReader(xmlContent)))
        doc.documentElement.normalize()
        return XmlNode(doc.documentElement)
    }
}

class XmlNode(
    val node: Element
) {

    override fun toString(): String {
        val builder = StringBuilder()
        printNode(builder, node, 0)
        return builder.toString()
    }

    private fun printNode(builder: StringBuilder, node: Element, currentDepth: Int): String {
        builder
            .append(indent(currentDepth))
            .append("<")
            .append(node.nodeName)
            .append("\n")

        node.attributes?.forEach {
            builder
                .append(indent(currentDepth + 1))
                .append(it.nodeName)
                .append("=\"")
                .append(it.nodeValue)
                .append("\"")
                .append("\n")
        }

        builder
            .append(indent(currentDepth + 1))
            .append(">")
            .append("\n")

        node.childNodes.forEach { child ->
            if (child.nodeType == Node.ELEMENT_NODE) {
                printNode(builder, child as Element, currentDepth + 1)
            }
        }

        builder
            .append(indent(currentDepth))
            .append("</")
            .append(node.nodeName)
            .append(">")
            .append("\n")

        return builder.toString()
    }

    private fun indent(level: Int): String = " ".repeat(level * 2)
}