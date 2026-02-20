package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * RPackageReader reads package data.
 */
class RPackageReader(private val manifestFile: File, private val logger: Logger) {

    fun readPackageName(): String? {
        if (!manifestFile.exists()) {
            logger.warn("Manifest file $manifestFile not exists")
            return null
        }

        // read package name in <manifest package="">
        val  factory= DocumentBuilderFactory.newInstance();
        val builder = factory.newDocumentBuilder()
        manifestFile.inputStream().use {
            val doc = builder.parse(it)
            val nodeList = doc.getElementsByTagName("manifest")
            if (nodeList.length == 0) {
                logger.warn("Manifest file $manifestFile not contains <manifest> tag")
                return null
            }
            val rPackage = nodeList.item(0).attributes?.getNamedItem("package")?.nodeValue
            if (rPackage == null) {
                logger.warn("Manifest file $manifestFile not contains package name")
                return null
            }
            logger.debug("Read package name $rPackage from manifest file $manifestFile")
            return rPackage
        }
    }
}
