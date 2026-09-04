package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.apk.manifest.BinaryXmlParser
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkFileModifierTest {

    @Before
    fun before() {
        clearBuild()
    }

    @Test
    fun testUpdateManifest() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")

        val copyApkFile = File(tempCompileDir, "out/${context.apkFile!!.name}")
        copyApkFile.delete()
        context.apkFile!!.copyTo(copyApkFile)
        assertTrue(copyApkFile.exists())
        assertEquals(context.apkFile!!.length(), copyApkFile.length())
        val apkFileModifier = ApkFileModifier(copyApkFile, context.signingConfig, context.androidHome, logger)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())
        apkFileModifier.insertAndResign()
        apkFileModifier.verify()

        val oldManifest = ApkReader(context.apkFile!!, logger).getManifest()
        val manifest = BinaryXmlParser.parseBinaryFromStream(outputFile.inputStream())
        val packageName = manifest.packageName()
        assertEquals(context.packageName, packageName)
        val activities = manifest.activities()
        assertEquals(oldManifest.activities().size + 1, activities.size)
    }

    @Test
    fun testUpdateManifestWithShellCharactersInApkPath() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")
        val copyApkFile = File(tempCompileDir, "out with space(测试)/My App_调试版(228).apk")
        copyApkFile.parentFile.mkdirs()
        context.apkFile!!.copyTo(copyApkFile, overwrite = true)
        val apkFileModifier = ApkFileModifier(copyApkFile, context.signingConfig, context.androidHome, logger)

        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())
        apkFileModifier.insertAndResign()
        apkFileModifier.verify()

        assertTrue(copyApkFile.exists())
    }

    @Test
    fun testSigningFailureKeepsOriginalApk() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")

        val copyApkFile = File(tempCompileDir, "out/${context.apkFile!!.name}")
        copyApkFile.delete()
        context.apkFile!!.copyTo(copyApkFile)
        val originalContent = copyApkFile.readBytes()
        val invalidSigningConfig = context.signingConfig.copy(storePassword = "invalid-password")
        val apkFileModifier = ApkFileModifier(copyApkFile, invalidSigningConfig, context.androidHome, logger)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())

        assertFailsWith<IllegalStateException> {
            apkFileModifier.insertAndResign()
        }

        assertContentEquals(originalContent, copyApkFile.readBytes())
    }
}
