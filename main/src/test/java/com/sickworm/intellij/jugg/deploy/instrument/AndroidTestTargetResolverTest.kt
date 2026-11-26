package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidTestTargetResolverTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `sourcePath resolves unique androidTest module and exact test apk by applicationId`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "com/example/FooTest.kt").apply {
            parentFile.mkdirs()
            writeText("class FooTest")
        }
        val module = androidTestModule(projectDir, "library1.androidTest", sourceRoot, "com.example.library1.test")
        val apk = testApk("com.example.library1.test", "com.example.library1")

        val result = AndroidTestTargetResolver.resolve(
            sourcePath = sourceFile.relativeTo(projectDir).path,
            projectDir = projectDir,
            modules = listOf(module),
            apks = listOf(apk),
        )

        assertEquals(module, result.module)
        assertEquals(apk, result.testApk)
    }

    @Test
    fun `sourcePath outside androidTest source roots returns clear error`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val outsideFile = File(projectDir, "library1/src/test/kotlin/FooTest.kt").apply {
            parentFile.mkdirs()
            writeText("class FooTest")
        }
        val module = androidTestModule(projectDir, "library1.androidTest", sourceRoot, "com.example.library1.test")

        val error = runCatching {
            AndroidTestTargetResolver.resolve(
                sourcePath = outsideFile.path,
                projectDir = projectDir,
                modules = listOf(module),
                apks = listOf(testApk("com.example.library1.test", "com.example.library1")),
            )
        }.exceptionOrNull()

        assertTrue(error is AndroidTestTargetResolveException)
        assertTrue(error!!.message!!.contains("sourcePath is not under any known androidTest source root"))
    }

    @Test
    fun `overlapping androidTest source roots require unique module match`() {
        val projectDir = temp.newFolder("project")
        val outerRoot = File(projectDir, "shared/src/androidTest").apply { mkdirs() }
        val innerRoot = File(outerRoot, "kotlin").apply { mkdirs() }
        val sourceFile = File(innerRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val outerModule = androidTestModule(projectDir, "outer.androidTest", outerRoot, "com.example.outer.test")
        val innerModule = androidTestModule(projectDir, "inner.androidTest", innerRoot, "com.example.inner.test")

        val error = runCatching {
            AndroidTestTargetResolver.resolve(
                sourcePath = sourceFile.path,
                projectDir = projectDir,
                modules = listOf(outerModule, innerModule),
                apks = listOf(
                    testApk("com.example.outer.test", "com.example.outer"),
                    testApk("com.example.inner.test", "com.example.inner"),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is AndroidTestTargetResolveException)
        assertTrue(error!!.message!!.contains("multiple androidTest modules match sourcePath"))
        assertTrue(error.message!!.contains("outer.androidTest"))
        assertTrue(error.message!!.contains("inner.androidTest"))
    }

    @Test
    fun `self-targeting library test apk can be resolved by target package when module applicationId differs`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val module = androidTestModule(
            projectDir = projectDir,
            name = "library1.androidTest",
            sourceRoot = sourceRoot,
            applicationId = "com.example.library1.generated.test",
            targetPackage = "com.example.library1.test",
        )
        val apk = testApk(
            applicationId = "com.example.library1.test",
            targetPackage = "com.example.library1.test",
        )

        val result = AndroidTestTargetResolver.resolve(
            sourcePath = sourceFile.path,
            projectDir = projectDir,
            modules = listOf(module),
            apks = listOf(apk),
        )

        assertEquals(apk, result.testApk)
    }

    @Test
    fun `app-style other-targeting test apk is not selected by target package fallback`() {
        val projectDir = temp.newFolder("project")
        val sourceRoot = File(projectDir, "library1/src/androidTest/kotlin").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "FooTest.kt").apply { writeText("class FooTest") }
        val module = androidTestModule(
            projectDir = projectDir,
            name = "library1.androidTest",
            sourceRoot = sourceRoot,
            applicationId = "com.example.library1.generated.test",
            targetPackage = "com.example.library1.test",
        )
        val appStyleApk = testApk(
            applicationId = "com.example.some.app.test",
            targetPackage = "com.example.library1.test",
        )

        val error = runCatching {
            AndroidTestTargetResolver.resolve(
                sourcePath = sourceFile.path,
                projectDir = projectDir,
                modules = listOf(module),
                apks = listOf(appStyleApk),
            )
        }.exceptionOrNull()

        assertTrue(error is AndroidTestTargetResolveException)
        assertTrue(error!!.message!!.contains("unable to resolve test APK"))
    }

    private fun androidTestModule(
        projectDir: File,
        name: String,
        sourceRoot: File,
        applicationId: String,
        targetPackage: String = "com.example.library1",
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = File(projectDir, name.substringBefore(".androidTest")),
            projectRootDir = projectDir,
            sourceDirs = listOf(sourceRoot),
            buildVariant = "debugAndroidTest",
            applicationId = applicationId,
            instrumentationTargetPackage = targetPackage,
            moduleDependencies = listOf(ModuleDependency(name.substringBefore(".androidTest"))),
            buildPathInfo = ModuleBuildPathInfo(projectDir, File(projectDir, name.substringBefore(".androidTest")), "debugAndroidTest", buildDirRelativePath = ""),
        )
    }

    private fun testApk(applicationId: String, targetPackage: String): ApkInfo {
        return ApkInfo(
            files = listOf(ApkFileUnit(applicationId, "", true, File("$applicationId.apk"))),
            applicationId = applicationId,
            instrumentationTargetPackage = targetPackage,
        )
    }
}
