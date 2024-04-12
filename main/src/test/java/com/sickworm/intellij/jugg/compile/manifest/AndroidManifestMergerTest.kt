package com.sickworm.intellij.jugg.compile.manifest

import com.sickworm.intellij.jugg.compiler.manifest.XmlParser
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class AndroidManifestMergerTest {

    @Test
    fun testMerge() {
        val xmlNode = XmlParser().parse(File(assetsAndroidDir, "app/src/main/AndroidManifest.xml"))
        val xmlContent = xmlNode.toString()
        println(xmlNode)

        val xmlNode2 = XmlParser().parse(xmlContent)
        val xmlContent2 = xmlNode2.toString()
        println(xmlNode2)

        assertEquals(xmlContent, xmlContent2)
    }

}