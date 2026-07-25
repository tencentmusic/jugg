package com.sickworm.intellij.jugg.compiler.compose

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Independently implements the Compose Multiplatform 1.7.3 CVR version 0 compatibility format.
 */
class ComposeValueResourceConverter {

    fun readLegacyStringNames(inputFile: File): List<String> {
        val root = parse(inputFile).documentElement
        require(root.tagName == "resources") { "Expected <resources> root: ${inputFile.absolutePath}" }
        val names = root.childElements(inputFile).map { element ->
            require(element.tagName == "string") {
                "Unsupported legacy value element ${element.tagName}: ${inputFile.absolutePath}"
            }
            element.getAttribute("name").also { name ->
                require(name.isNotEmpty()) { "Invalid resource name '$name': ${inputFile.absolutePath}" }
            }
        }
        require(names.distinct().size == names.size) { "Duplicate legacy string resource: ${inputFile.absolutePath}" }
        return names
    }

    fun convert(inputFile: File, outputFile: File) {
        val records = readRecords(parse(inputFile), inputFile)
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(buildString {
            append("version:0\n")
            records.sorted().forEach { append(it).append('\n') }
        }, Charsets.UTF_8)
    }

    private fun parse(inputFile: File): Document {
        return try {
            secureDocumentBuilderFactory().newDocumentBuilder().apply {
                setErrorHandler(object : ErrorHandler {
                    override fun warning(exception: SAXParseException) = throw exception
                    override fun error(exception: SAXParseException) = throw exception
                    override fun fatalError(exception: SAXParseException) = throw exception
                })
            }.parse(inputFile)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Malformed values XML: ${inputFile.absolutePath}", exception)
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

    private fun readRecords(document: Document, inputFile: File): List<String> {
        val root = document.documentElement
        require(root.tagName == "resources") { "Expected <resources> root: ${inputFile.absolutePath}" }
        val keys = mutableSetOf<String>()
        return root.childElements(inputFile).map { element ->
            val record = createRecord(element, inputFile)
            val key = record.substringBeforeLast('|')
            require(keys.add(key)) { "Duplicate resource $key: ${inputFile.absolutePath}" }
            record
        }
    }

    private fun createRecord(element: Element, inputFile: File): String {
        val type = element.tagName
        require(type in SUPPORTED_TYPES) {
            "Unsupported value element $type: ${inputFile.absolutePath}"
        }
        val name = element.getAttribute("name")
        require(name.isNotEmpty() && name.none { it == '|' || it == '\r' || it == '\n' }) {
            "Invalid resource name '$name': ${inputFile.absolutePath}"
        }
        val content = when (type) {
            "string" -> encode(element.textContent)
            "string-array" -> itemElements(element, inputFile).joinToString(",") { encode(it.textContent) }
            else -> pluralContent(element, inputFile)
        }
        return "$type|$name|$content"
    }

    private fun pluralContent(element: Element, inputFile: File): String {
        val quantities = mutableSetOf<String>()
        return itemElements(element, inputFile).joinToString(",") { item ->
            val quantity = item.getAttribute("quantity")
            require(quantity in PLURAL_QUANTITIES) {
                "Invalid plural quantity $quantity: ${inputFile.absolutePath}"
            }
            require(quantities.add(quantity)) {
                "Duplicate plural quantity $quantity: ${inputFile.absolutePath}"
            }
            "${quantity.uppercase(Locale.ROOT)}:${encode(item.textContent)}"
        }
    }

    private fun itemElements(element: Element, inputFile: File): List<Element> =
        element.childElements(inputFile).onEach {
            require(it.tagName == "item") {
                "Unsupported ${element.tagName} child ${it.tagName}: ${inputFile.absolutePath}"
            }
        }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(
        decodeComposeEscapes(value).toByteArray(Charsets.UTF_8),
    )

    private fun decodeComposeEscapes(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] != '\\' || index == value.lastIndex) {
                append(value[index++])
                continue
            }
            val escaped = value[index + 1]
            when {
                escaped == '\\' -> append('\\').also { index += 2 }
                escaped == 'n' -> append('\n').also { index += 2 }
                escaped == 't' -> append('\t').also { index += 2 }
                escaped == 'u' && value.hasUnicodeEscapeAt(index) -> {
                    append(value.substring(index + 2, index + 6).toInt(16).toChar())
                    index += 6
                }
                else -> append(value[index++])
            }
        }
    }

    private fun Element.childElements(inputFile: File): List<Element> {
        val elements = mutableListOf<Element>()
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            when (child.nodeType) {
                Node.ELEMENT_NODE -> elements.add(child as Element)
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> require(child.textContent.isBlank()) {
                    "Unexpected text in <$tagName>: ${inputFile.absolutePath}"
                }
            }
        }
        return elements
    }

    private fun String.hasUnicodeEscapeAt(index: Int): Boolean = index + 6 <= length &&
        substring(index + 2, index + 6).all { it.digitToIntOrNull(16) != null }

    private companion object {
        val PLURAL_QUANTITIES = setOf("zero", "one", "two", "few", "many", "other")
        val SUPPORTED_TYPES = setOf("string", "string-array", "plurals")
    }
}
