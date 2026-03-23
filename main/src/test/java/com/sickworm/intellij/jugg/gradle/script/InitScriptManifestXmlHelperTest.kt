package com.sickworm.intellij.jugg.gradle.script

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitScriptManifestXmlHelperTest {

    @Test
    fun replaceApplication_shouldInjectBootstrapEntriesAndKeepOriginalValues() {
        val manifestFile = createManifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.demo">
                <application
                    android:name="com.example.OriginalApplication"
                    android:appComponentFactory="com.example.OriginalFactory" />
            </manifest>
            """.trimIndent(),
        )

        InitScriptManifestXmlHelper(manifestFile).replaceApplication(
            applicationName = "com.sickworm.intellij.jugg.hotfix.BootstrapApplication",
            rawApplicationMetaDataName = "com.sickworm.intellij.jugg.hotfix.raw.application",
            appComponentFactoryName = "com.sickworm.intellij.jugg.hotfix.BootstrapAppComponentFactory",
            rawAppComponentFactoryMetaDataName = "com.sickworm.intellij.jugg.hotfix.raw.appComponentFactory",
        )

        val output = manifestFile.readText()
        assertTrue(output.contains("android:name=\"com.sickworm.intellij.jugg.hotfix.BootstrapApplication\""))
        assertTrue(output.contains("android:appComponentFactory=\"com.sickworm.intellij.jugg.hotfix.BootstrapAppComponentFactory\""))
        assertTrue(output.contains("android:value=\"com.example.OriginalApplication\""))
        assertTrue(output.contains("android:value=\"com.example.OriginalFactory\""))
        assertTrue(output.contains("com.sickworm.intellij.jugg.hotfix.raw.application"))
        assertTrue(output.contains("com.sickworm.intellij.jugg.hotfix.raw.appComponentFactory"))
    }

    @Test
    fun replaceApplication_shouldAddNameWhenManifestHasNoApplicationName() {
        val manifestFile = createManifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.demo">
                <application />
            </manifest>
            """.trimIndent(),
        )

        InitScriptManifestXmlHelper(manifestFile).replaceApplication(
            applicationName = "com.sickworm.intellij.jugg.hotfix.BootstrapApplication",
            rawApplicationMetaDataName = "com.sickworm.intellij.jugg.hotfix.raw.application",
            appComponentFactoryName = "com.sickworm.intellij.jugg.hotfix.BootstrapAppComponentFactory",
            rawAppComponentFactoryMetaDataName = "com.sickworm.intellij.jugg.hotfix.raw.appComponentFactory",
        )

        val output = manifestFile.readText()
        assertTrue(output.contains("android:name=\"com.sickworm.intellij.jugg.hotfix.BootstrapApplication\""))
        assertFalse(output.contains("com.sickworm.intellij.jugg.hotfix.raw.application"))
    }

    private fun createManifest(content: String): File {
        val dir = Files.createTempDirectory("manifest_helper_test").toFile()
        val manifestFile = File(dir, "AndroidManifest.xml")
        manifestFile.writeText(content)
        return manifestFile
    }
}
