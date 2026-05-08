package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryTestApkBackfillPlannerTest {

    @Test
    fun `plan derives only the current androidTest module task and apk pattern`() {
        val projectDir = File("/repo")
        val module = androidTestModule(projectDir, "library1.androidTest", "developmentDebugAndroidTest")

        val plan = LibraryTestApkBackfillPlanner.plan(module)

        assertEquals(":library1:assembleDevelopmentDebugAndroidTest", plan.gradleTask)
        assertEquals(
            "library1/build/outputs/apk/androidTest/development/debug/*.apk",
            plan.outputApkPattern,
        )
    }

    @Test
    fun `plan treats plain debug androidTest variant as debug path`() {
        val projectDir = File("/repo")
        val module = androidTestModule(projectDir, "feature.login.androidTest", "debugAndroidTest")

        val plan = LibraryTestApkBackfillPlanner.plan(module)

        assertEquals(":feature:login:assembleDebugAndroidTest", plan.gradleTask)
        assertEquals(
            "feature/login/build/outputs/apk/androidTest/debug/*.apk",
            plan.outputApkPattern,
        )
    }

    private fun androidTestModule(projectDir: File, name: String, buildVariant: String): ModuleInfo {
        val modulePath = name.substringBefore(".androidTest").replace('.', File.separatorChar)
        val moduleRoot = File(projectDir, modulePath)
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleRootDir = moduleRoot,
            projectRootDir = projectDir,
            buildVariant = buildVariant,
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleRoot, buildVariant),
            applicationId = "com.example.$name",
            instrumentationTargetPackage = "com.example.$name",
        )
    }
}
