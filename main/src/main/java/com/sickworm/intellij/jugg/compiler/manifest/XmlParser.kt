package com.sickworm.intellij.jugg.compiler.manifest

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


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

    val isEmpty: Boolean
        get() = node.childNodes.length == 0

    fun printXml(): String {
        val out = ByteArrayOutputStream()
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.VERSION, "1.0")
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        tf.setOutputProperty(OutputKeys.INDENT, "yes")
        tf.transform(DOMSource(node), StreamResult(out))
        return out.toString("UTF-8")
            .lines()
            .filter { it.trim().isNotEmpty() }
            .joinToString("\n")
    }

    private fun indent(level: Int): String = " ".repeat(level * 2)
}
