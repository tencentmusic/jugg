package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.ComposeResourceDirectory
import com.sickworm.intellij.jugg.project.info.ComposeResourceInfo
import com.sickworm.intellij.jugg.project.info.ComposeResourceSupportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectInfoSerializerInGradleAndroidTestTest {

    private fun projectInfoWithoutAgpR8(modules: Map<String, ModuleInfo>) =
        JuggProjectInfo(modules, agpR8Classpath = null)

    private fun composeInfo() = ComposeResourceInfo(
        generatorClasspath = listOf(File("/gradle/compose-gradle-plugin-1.7.3.jar"), File("/gradle/kotlin-stdlib-2.1.0.jar")),
        packageName = "com.example.generated.resources",
        publicResClass = true,
        resourceDirectories = listOf(
            ComposeResourceDirectory("commonMain", File("/project/shared/src/commonMain/composeResources")),
            ComposeResourceDirectory("androidMain", File("/project/shared/src/androidMain/customComposeResources")),
        ),
        assetRelativePath = "composeResources/com.example.generated.resources",
        resClassName = "AppRes",
        generateResourceContentHash = true,
        usesLegacyGenerator = true,
        supportStatus = ComposeResourceSupportStatus.Unsupported,
        unsupportedReason = "Unsupported Kotlin 2.0.21; Jugg Compose resources require Kotlin 2.1.x.",
    )

    private fun androidTestModule() = ModuleInfo.virtualModule.copy(
        name = "app.androidTest",
        moduleType = ModuleInfo.Type.Library,
        moduleRootDir = File("/project/app"),
        projectRootDir = File("/project"),
        applicationId = "com.example.app.test",
        instrumentationTargetPackage = "com.example.app",
        buildVariant = "debugAndroidTest",
        buildPathInfo = ModuleBuildPathInfo(File("/project"), File("/project/app"), "debugAndroidTest", buildDirRelativePath = ""),
    )

    @Test
    fun `save and load round-trip preserves instrumentationTargetPackage`() {
        val tmpFile = Files.createTempFile("jugg_test_", ".json").toFile()
        try {
            val serializer = ProjectInfoSerializerInGradle(tmpFile)
            val original = projectInfoWithoutAgpR8(mapOf("app.androidTest" to androidTestModule()))
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
                buildPathInfo = ModuleBuildPathInfo(File("/project"), File("/project/app"), "debug", buildDirRelativePath = ""),
            )
            val original = projectInfoWithoutAgpR8(mapOf("app" to regular))
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

    @Test
    fun `save and load round-trip preserves Compose resource info`() {
        val tmpFile = Files.createTempFile("jugg_test_", ".json").toFile()
        try {
            val serializer = ProjectInfoSerializerInGradle(tmpFile)
            val original = projectInfoWithoutAgpR8(mapOf(
                "shared" to ModuleInfo.virtualModule.copy(
                    name = "shared",
                    composeResourceInfo = composeInfo(),
                )
            ))

            serializer.save(original)
            val loaded = serializer.load()

            assertEquals(
                composeInfo(),
                loaded?.modules?.first { it.moduleInfoExceptLibraries.name == "shared" }
                    ?.moduleInfoExceptLibraries?.composeResourceInfo,
            )
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `save and load round-trip preserves AGP R8 classpath`() {
        val tmpFile = Files.createTempFile("jugg_test_", ".json").toFile()
        try {
            val serializer = ProjectInfoSerializerInGradle(tmpFile)
            val r8Classpath = File("/gradle/caches/builder-9.2.0.jar")
            serializer.save(JuggProjectInfo(emptyMap(), agpR8Classpath = r8Classpath))

            val loaded = serializer.load()

            assertEquals(r8Classpath, loaded?.juggProjectInfoExceptModules?.agpR8Classpath)
        } finally {
            tmpFile.delete()
        }
    }
}
