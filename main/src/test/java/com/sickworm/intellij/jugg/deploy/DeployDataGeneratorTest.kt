package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.data.convertClassToSigFormat
import com.sickworm.intellij.jugg.deploy.run.ClassDeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeployDataGeneratorTest {

    private val abcParsedDexMock: ParsedDex = getAdbParsedDex()

    private fun getAdbParsedDex(): ParsedDex {
        val parsedApk = ApkParser().parse(projectInfo.apkInfo)
        val className = "com.example.myapplication.ABC"
        val classNode = parsedApk.classes[className.convertClassToSigFormat()]!!
        val deployItem = DeployItem(className, CompileOutput.Type.Dex, 0, byteArrayOf())
        return ParsedDex(
            listOf(ClassDeployItem(deployItem, classNode)),
            emptyMap(),
            emptyMap(),
        )
    }

    @Test
    fun testOverlayContents() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        val overlayDeployItem = DeployItem("test_overlay", CompileOutput.Type.Overlay, 0, byteArrayOf())
        val data = generator.buildDeployData(listOf(overlayDeployItem), false)
        assertEquals(475, data.overlays.size)
        assertTrue(data.isFullOverlays)
        logger.debug(data.toString())
    }

    @Test
    fun testHotModified() {
        val generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
        val data = generator.buildDeployData(abcParsedDexMock, emptyList())
        assertEquals(0, data.newClasses.size)
        assertEquals(1, data.hotReloadModifiedClasses.size)
        assertEquals(0, data.hotFixModifiedClasses.size)
        assertEquals(0, data.effectedSourceFileNames.size)
    }
}