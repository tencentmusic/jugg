package com.sickworm.intellij.jugg.project.info

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.context.CompileEnvironmentSource
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File

/** Verifies Gradle-only project model loading and compile context creation. */
class ProjectModelSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun gradleOnlyModel_createsCompileContextWithoutIdeProjectInfo() {
        val projectDir = temporaryFolder.newFolder("gradle_only")
        val pathManager = JuggPathManager(projectDir)
        saveGradleProjectInfo(pathManager, createProjectInfo(projectDir, listOf("app")))
        val androidHome = temporaryFolder.newFolder("android_home")
        File(androidHome, "platforms/android-30/android.jar").also {
            it.parentFile.mkdirs()
            it.createNewFile()
        }
        val compileContextManager = CompileContextManager(
            pathManager = pathManager,
            projectModelSource = GradleProjectModelSource(pathManager, TestGlobal.logger),
            deployFileManager = mock<DeployFileManager>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            compileEnvironmentSource = CompileEnvironmentSource(androidHome, listOf("JAVA_HOME=/java")),
            scene = ICompileContext.Scene.IDE,
            logger = TestGlobal.logger,
        )

        val context = compileContextManager.compileContext

        assertEquals(setOf("app"), context.modules.keys)
        assertEquals(projectDir.canonicalFile, context.projectDir.canonicalFile)
        assertEquals(listOf("JAVA_HOME=/java"), context.cmdCompileEnv)
        assertTrue(context.applicationModule?.moduleType == ModuleInfo.Type.Application)
    }

    @Test
    fun gradleOnlyModel_filtersAndroidTestModuleByBuildTarget() {
        val projectDir = temporaryFolder.newFolder("android_test_target")
        val pathManager = JuggPathManager(projectDir)
        val app = createProjectInfo(projectDir, listOf("app")).modules.getValue("app")
        val androidTest = app.copy(
            name = "app.androidTest",
            moduleType = ModuleInfo.Type.Library,
            applicationId = "com.example.app.test",
            instrumentationTargetPackage = "com.example.app",
        )
        saveGradleProjectInfo(pathManager, JuggProjectInfo(linkedMapOf(app.name to app, androidTest.name to androidTest), agpR8Classpath = null))
        val source = GradleProjectModelSource(pathManager, TestGlobal.logger)

        val appModel = source.load(ProjectModelLoadReason.INITIALIZE, BuildTarget.APP).projectInfo!!
        val androidTestModel = source.load(ProjectModelLoadReason.INITIALIZE, BuildTarget.ANDROID_TEST).projectInfo!!

        assertEquals(setOf("app"), appModel.modules.keys)
        assertEquals(setOf("app", "app.androidTest"), androidTestModel.modules.keys)
    }

    private fun saveGradleProjectInfo(pathManager: JuggPathManager, projectInfo: JuggProjectInfo) {
        ProjectInfoSerializer(pathManager.gradleProjectInfoFile, TestGlobal.logger).save(projectInfo)
    }

    private fun createProjectInfo(projectDir: File, moduleNames: List<String>): JuggProjectInfo {
        val modules = linkedMapOf<String, ModuleInfo>()
        moduleNames.forEach { name ->
            val moduleDir = File(projectDir, name)
            modules[name] = ModuleInfo.virtualModule.copy(
                name = name,
                moduleType = if (name == "app") ModuleInfo.Type.Application else ModuleInfo.Type.Library,
                projectRootDir = projectDir,
                moduleRootDir = moduleDir,
                sourceDirs = listOf(File(moduleDir, "src/main/java")),
                resourceDirs = listOf(File(moduleDir, "src/main/res")),
                manifestFile = File(moduleDir, "src/main/AndroidManifest.xml"),
                compileVersion = "30",
                minSdkVersion = "21",
                buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, ModuleInfo.DEFAULT_BUILD_VARIANT, buildDirRelativePath = ""),
            )
        }
        return JuggProjectInfo(modules, agpR8Classpath = null)
    }
}
