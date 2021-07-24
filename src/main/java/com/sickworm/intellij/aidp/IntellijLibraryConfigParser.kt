package com.sickworm.intellij.aidp

import com.android.tools.idea.memorysettings.GradlePropertiesUtil
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.plugins.gradle.util.USER_HOME
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

private val logger = Logger.getInstance("#AIDP-IntellijLibraryConfigParser")

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
            .mapNotNull { parse(it.absolutePath) }
            .toList()
    }

    private fun parse(path: String): String? {
        val handler = Handler()
        parser.parse(path, handler)
        val jarFilePath = handler.jarFile
        if (jarFilePath == null) {
            logger.warn("can not read library info from: $path")
            return null
        }
        return jarFilePath
            .replace("\$USER_HOME\$", userHome)
            .replace("\$PROJECT_DIR\$", projectDir)
    }

    private class Handler: DefaultHandler() {
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