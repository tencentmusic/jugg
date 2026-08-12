package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.TestPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApkParserProcessLauncherTest {

    @Before
    fun setUp() {
        PlatformApi.impl = TestPlatformApi()
    }

    @Test
    fun `buildProcessCommand passes apk files as url safe base64`() {
        val launcher = ApkParserProcessLauncher(logger)
        val apkFilesJson = launcher.callSerializeApkFiles(
            listOf(
                ApkFileUnit(
                    applicationId = "com.example.myapplication",
                    moduleName = "",
                    debuggable = true,
                    apkFile = File(buildDir, "app-debug.apk"),
                )
            )
        )

        val command = launcher.callBuildProcessCommand(
            dbDir = buildDir,
            apkFilesJson = apkFilesJson,
            applicationId = "com.example.myapplication",
            outputFile = File(buildDir, "apk_parse_result.json"),
        )

        val mainClassIndex = command.indexOf("com.sickworm.intellij.jugg.deploy.data.ApkParserProcess")
        val encodedApkFilesJson = command[mainClassIndex + 2]

        assertFalse(encodedApkFilesJson.startsWith("[{"))
        assertFalse(encodedApkFilesJson.contains("\""))
        assertFalse(encodedApkFilesJson.contains("+"))
        assertFalse(encodedApkFilesJson.contains("/"))
        assertEquals(apkFilesJson, String(Base64.getUrlDecoder().decode(encodedApkFilesJson), Charsets.UTF_8))
        assertEquals(apkFilesJson, callDecodeApkFilesJson(encodedApkFilesJson))
    }

    @Suppress("UNCHECKED_CAST")
    private fun ApkParserProcessLauncher.callBuildProcessCommand(
        dbDir: File,
        apkFilesJson: String,
        applicationId: String,
        outputFile: File,
    ): List<String> {
        val method = ApkParserProcessLauncher::class.java.getDeclaredMethod(
            "buildProcessCommand",
            File::class.java,
            String::class.java,
            String::class.java,
            File::class.java,
        )
        method.isAccessible = true
        return method.invoke(this, dbDir, apkFilesJson, applicationId, outputFile) as List<String>
    }

    private fun ApkParserProcessLauncher.callSerializeApkFiles(apkFiles: List<ApkFileUnit>): String {
        val method = ApkParserProcessLauncher::class.java.getDeclaredMethod("serializeApkFiles", List::class.java)
        method.isAccessible = true
        return method.invoke(this, apkFiles) as String
    }

    private fun callDecodeApkFilesJson(encodedJson: String): String {
        val method = ApkParserProcess::class.java.getDeclaredMethod("decodeApkFilesJson", String::class.java)
        method.isAccessible = true
        return method.invoke(ApkParserProcess, encodedJson) as String
    }
}
