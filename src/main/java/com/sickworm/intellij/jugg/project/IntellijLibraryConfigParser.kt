package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.plugins.gradle.util.USER_HOME
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

private val logger = Logger.getInstance("#JUGG-IntellijLibraryConfigParser")

class IntellijLibraryConfigParser(private val configDir: File, private val projectDir: String) {

    private val parser = SAXParserFactory.newInstance().newSAXParser()

    private val userHome = System.getProperty(USER_HOME)

    /**
     * @return library path list
     */
    fun parse(): List<String>? {
        if (!configDir.exists()) {
            logger.warn("config dir not exist: $configDir")
            return null
        }

        return configDir
            .walkTopDown()
            .filter { it.name.endsWith(".xml") }
            .flatMap { parse(it.absolutePath) }
            .toList()
    }

    private fun parse(path: String): List<String> {
        val handler = Handler()
        parser.parse(path, handler)
        val jarFilePath = handler.jarFile
        if (jarFilePath.isEmpty()) {
            logger.warn("can not read library info from: $path")
            return emptyList()
        }
        return jarFilePath
            .map {
                it.replace("\$USER_HOME\$", userHome)
                    .replace("\$PROJECT_DIR\$", projectDir)
            }
            .filter {
                File(it).exists()
            }
    }

    private class Handler: DefaultHandler() {
        var jarFile: MutableList<String> = mutableListOf()
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
                    jarFile.add(jarFileUri.substring(6, jarFileUri.length - 2))
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