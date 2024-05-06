package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.mock.assetsDir
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class XmlParserTest {

    @Test
    fun testPrintXml() {
        val xmlNode = XmlParser().parse(File(assetsDir, "android/manifest/merged.xml"))
        val xmlContent = xmlNode.printXml()
        println(xmlContent)

        val xmlNode2 = XmlParser().parse(xmlContent)
        val xmlContent2 = xmlNode2.printXml()
        println(xmlContent2)

        val splits2 = xmlContent2.split("\n")
        xmlContent.split("\n").forEachIndexed { index, s ->
            assertEquals(s.trim(), splits2[index].trim(), "Line ${index + 1}")
        }
    }

}