package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Test
import kotlin.test.assertEquals

class ApkInfoSerializerTest {

    private val projectInfo = TestGlobal.projectInfo

    @Test
    fun testSerialize() {
        val apkInfo = projectInfo.apkInfos
        val serializer = ApkInfoSerializer()
        val json = serializer.serialize(projectInfo.projectRoot, apkInfo)
        println(json)
        val newApkInfo = serializer.deserialize(projectInfo.projectRoot, json)
        assertEquals(apkInfo.size, newApkInfo.size)

        apkInfo.forEachIndexed { index, _ ->
            val a = apkInfo[index]
            val b = newApkInfo[index]
            assertEquals(a.applicationId, b.applicationId)
            assertEquals(a.files.size, b.files.size)
            a.files.forEachIndexed { fileIndex, _ ->
                val aFile = a.files[fileIndex]
                val bFile = b.files[fileIndex]
                assertEquals(aFile.moduleName, bFile.moduleName)
                assertEquals(aFile.apkFile.absolutePath, bFile.apkFile.absolutePath)
            }
        }

        val newJson = serializer.serialize(projectInfo.projectRoot, apkInfo)
        println(newJson)
        assertEquals(json, newJson)
    }
}