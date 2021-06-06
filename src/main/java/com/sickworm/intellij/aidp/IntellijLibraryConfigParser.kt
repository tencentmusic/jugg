package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

private val logger = Logger.getInstance("#AIDP-IntellijLibraryConfigParser")

class IntellijLibraryConfigParser(private val configDirPath: String) {

    private val parser = SAXParserFactory.newInstance().newSAXParser()

    /**
     * @return library path list
     */
    fun parse(): List<String>? {
        val configDir = File(configDirPath)
        if (!configDir.exists()) {
            logger.warn("config dir not exist: $configDirPath")
            return null
        }

        return configDir
            .walkTopDown()
            .filter { it.name.endsWith(".xml") }
            .mapNotNull { parse(it.absolutePath) }
            .toList()
    }

    private fun parse(path: String): String? {
        val handler = Handler(path)
        parser.parse(path, handler)
        val jarFilePath = handler.jarFile
        if (jarFilePath == null) {
            logger.warn("can not read library info from: $path")
        }
        return jarFilePath
    }

    private class Handler(private val path: String): DefaultHandler() {
        var jarFile: String? = null
        var isClasses: Boolean = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (qName == "CLASSES") {
                isClasses = true
                return
            }
            if (isClasses) {
                if (qName == "root") {
                    val jarFileUri = attributes?.getValue(0)?: run {
                        return
                    }
                    if (!jarFileUri.startsWith("jar://") || !jarFileUri.endsWith("!/")) {
                        return
                    }
                    jarFile = jarFileUri.substring(6, jarFileUri.length - 2)
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (qName == "CLASSES") {
                isClasses = false
            }
        }
    }
}