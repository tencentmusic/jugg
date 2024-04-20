package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.apk.manifest.ManifestActivityInfo
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val manifest = ManifestActivityInfo.parseBinaryFromStream(outputFile.inputStream())
        val packageName = manifest.packageName()
        assertEquals(context.packageName, packageName)
        val activities = manifest.activities()
        assertEquals(oldManifest.activities().size + 1, activities.size)
    }
}