package com.sickworm.intellij.jugg.compiler.manifest

import java.io.File

class XmlAndroidManifestInfo {

    var packageName: String? = null
        private set

    companion object {
        fun parse(file: File): XmlAndroidManifestInfo {
            val xmlNode: XmlNode = XmlParser().parse(file)
            val info = XmlAndroidManifestInfo()
            var packageName: String? = null
            // XmlNodeExt is not in readProjectInfo.gradle.kts, so I can't use it here.
            (0 until xmlNode.node.attributes.length).forEach { i ->
                val attr = xmlNode.node.attributes.item(i)
                if (attr.nodeName == "package") {
                    packageName = attr.nodeValue
                }
            }
            info.packageName = packageName
            return info
        }
    }
}