package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectInfoSerializerInGradleAndroidTestTest {

    private fun androidTestModule() = ModuleInfo.virtualModule.copy(
        name = "app.androidTest",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = File("/project/app"),
        projectRootDir = File("/project"),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(File("/project"), File("/project/app"), "debugAndroidTest"),
    )

    @Test
    fun `save and load round-trip preserves instrumentationTargetPackage`() {
        val tmpFile = Files.createTempFile("jugg_test_", ".json").toFile()
        try {
            val serializer = ProjectInfoSerializerInGradle(tmpFile)
            val original = JuggProjectInfo(mapOf("app.androidTest" to androidTestModule()))
            serializer.save(original)
            val loaded = serializer.load()
            assertEquals(
                "com.example.app",
                loaded?.modules?.first { it.moduleInfoExceptLibraries.name == "app.androidTest" }
                    ?.moduleInfoExceptLibraries?.instrumentationTargetPackage
            )
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `load returns null instrumentationTargetPackage for regular module`() {
        val tmpFile = Files.createTempFile("jugg_test_", ".json").toFile()
        try {
            val serializer = ProjectInfoSerializerInGradle(tmpFile)
            val regular = ModuleInfo.virtualModule.copy(
                name = "app",
                moduleType = ModuleInfo.Type.Application,
                moduleRootDir = File("/project/app"),
                projectRootDir = File("/project"),
                buildPathInfo = ModuleBuildPathInfo(File("/project"), File("/project/app"), "debug"),
            )
            val original = JuggProjectInfo(mapOf("app" to regular))
            serializer.save(original)
            val loaded = serializer.load()
            assertNull(
                loaded?.modules?.first { it.moduleInfoExceptLibraries.name == "app" }
                    ?.moduleInfoExceptLibraries?.instrumentationTargetPackage
            )
        } finally {
            tmpFile.delete()
        }
    }
}
