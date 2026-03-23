package com.sickworm.intellij.jugg.gradle.script

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
private val ANDROID_PREFIX = "android"
private val TAG_APPLICATION = "application"
private val TAG_META_DATA = "meta-data"
private val ATTRIBUTE_NAME = "name"
private val ATTRIBUTE_VALUE = "value"
private val ATTRIBUTE_APP_COMPONENT_FACTORY = "appComponentFactory"
private val ATTRIBUTE_BACKUP_AGENT = "backupAgent"

/**
 * InitScriptManifestXmlHelper updates AndroidManifest.xml with only JDK DOM APIs for init-script usage.
 */
class InitScriptManifestXmlHelper(
    private val manifestFile: File,
) {

    fun replaceApplication(
        applicationName: String,
        rawApplicationMetaDataName: String,
        appComponentFactoryName: String,
        rawAppComponentFactoryMetaDataName: String,
    ) {
        val document = readDocument()
        val application = findApplication(document)
        println("Jugg application node exists: true")

        val originApplicationName = application.getAndroidAttribute(ATTRIBUTE_NAME)
        val originAppComponentName = application.getAndroidAttribute(ATTRIBUTE_APP_COMPONENT_FACTORY)
        println("Jugg attributes size: ${application.attributes.length}")

        application.setAndroidAttribute(ATTRIBUTE_NAME, applicationName)
        if (originAppComponentName != null) {
            application.setAndroidAttribute(ATTRIBUTE_APP_COMPONENT_FACTORY, appComponentFactoryName)
        }

        if (originApplicationName == null) {
            println("Jugg: originApplicationName is null, add name attribute to application")
        } else if (originApplicationName != applicationName) {
            putMetaData(application, rawApplicationMetaDataName, originApplicationName)
        }

        if (originAppComponentName == null) {
            println("Jugg: originAppComponentName is null, no need to handle")
        } else if (originAppComponentName != appComponentFactoryName) {
            putMetaData(application, rawAppComponentFactoryMetaDataName, originAppComponentName)
            application.setAndroidAttribute(ATTRIBUTE_BACKUP_AGENT, rawAppComponentFactoryMetaDataName)
        }

        writeDocument(document)
    }

    private fun findApplication(document: Document): Element {
        val application = document.documentElement
            ?.getElementsByTagName(TAG_APPLICATION)
            ?.item(0) as? Element
        if (application != null) {
            return application
        }
        throw IllegalStateException("Wrong format in AndroidManifest, no application node is found !")
    }

    private fun putMetaData(application: Element, name: String, value: String) {
        val metaData = findMetaData(application, name)
            ?: application.ownerDocument.createElement(TAG_META_DATA).also(application::appendChild)
        metaData.setAndroidAttribute(ATTRIBUTE_NAME, name)
        metaData.setAndroidAttribute(ATTRIBUTE_VALUE, value)
    }

    private fun findMetaData(application: Element, name: String): Element? {
        val children = application.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) {
                continue
            }
            val element = node as? Element ?: continue
            if (element.tagName != TAG_META_DATA) {
                continue
            }
            if (element.getAndroidAttribute(ATTRIBUTE_NAME) == name) {
                return element
            }
        }
        return null
    }

    private fun readDocument(): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(manifestFile)
    }

    private fun setFeatureIfSupported(factory: DocumentBuilderFactory, name: String, enabled: Boolean) {
        try {
            factory.setFeature(name, enabled)
        } catch (_: Throwable) {
            println("Jugg: ignore unsupported xml feature $name")
        }
    }

    private fun writeDocument(document: Document) {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8")
        transformer.transform(DOMSource(document), StreamResult(manifestFile))
    }

    private fun Element.getAndroidAttribute(attributeName: String): String? {
        val value = getAttributeNS(ANDROID_NAMESPACE, attributeName)
        return value.takeIf { it.isNotEmpty() }
    }

    private fun Element.setAndroidAttribute(attributeName: String, value: String) {
        setAttributeNS(ANDROID_NAMESPACE, "$ANDROID_PREFIX:$attributeName", value)
    }
}
