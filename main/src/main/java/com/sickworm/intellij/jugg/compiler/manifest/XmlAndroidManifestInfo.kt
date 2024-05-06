package com.sickworm.intellij.jugg.compiler.manifest

import java.io.File

class XmlAndroidManifestInfo {

    var packageName: String? = null
        private set

    companion object {
        fun parse(file: File): XmlAndroidManifestInfo {
            val xmlNode = XmlParser().parse(file)
            val info = XmlAndroidManifestInfo()
            val packageName = xmlNode.node["package"]
            info.packageName = packageName
            return info
        }
    }
}