package com.sickworm.intellij.jugg.tools

import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import local.main.logger
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val apkFilePath = """
../idea/src/test/assets/android/MyApplicationIntellij/app/build/outputs/apk/debug/app-debug.apk
""".split("\n").filter { it.isNotBlank() }.joinToString("")

private val gradleProjectInfoPath = """
../idea/src/test/assets/android/MyApplicationIntellij/build/jugg/database/project_infos.db/gradle_project_infos.json
""".split("\n").filter { it.isNotBlank() }.joinToString("")

class ResignApk {

    @Test
    fun test() {
        AssembleAndroidProjectOnce.ensure()
        val apkFile = File(apkFilePath)
        assertTrue(apkFile.exists(), "Apk file not found: ${apkFile.absolutePath}")
        val gradleProjectInfoFile = File(gradleProjectInfoPath)
        assertTrue(gradleProjectInfoFile.exists(), "Gradle project info file not found: $gradleProjectInfoPath")

        val gradleProjectInfo = ProjectInfoSerializer(gradleProjectInfoFile, logger).load(isSkipVersionCheck = true)
        assertNotNull(gradleProjectInfo, "Gradle project info not found")
        val signingConfig = gradleProjectInfo.modules.values.find { it.signingConfigs != null }?.signingConfigs?.firstOrNull()
        assertNotNull(signingConfig, "Signing config not found")

        ApkFileModifier(apkFile, signingConfig, TestGlobal.androidHome, logger).insertAndResign()
    }
}