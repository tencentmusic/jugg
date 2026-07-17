package com.sickworm.intellij.jugg.compiler.context

import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.mock.androidHome as testAndroidHome
import com.sickworm.intellij.jugg.project.info.GradleProjectModelSource
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.info.IProjectModelSource
import com.sickworm.intellij.jugg.project.info.ProjectModelLoadReason
import com.sickworm.intellij.jugg.project.info.ProjectModelResult
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.server.protocols.ModuleCustomConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Verifies full-build compile context paths stay authoritative after runtime context updates.
 */
class CompileContextManagerBuildPathInfoTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun updateCustomClasspath_keepsCompileContextBuildPathInfo() {
        TestGlobal.init()
        val projectDir = temporaryFolder.newFolder("project")
        val appDir = projectDir.resolve("app").also { it.mkdirs() }
        val pathManager = JuggPathManager(projectDir)
        val ideModule = createModule(projectDir, appDir)
        ProjectInfoSerializer(pathManager.gradleProjectInfoFile, TestGlobal.logger)
            .save(JuggProjectInfo(mapOf(ideModule.name to ideModule), agpR8Classpath = null))
        val manager = createManager(pathManager)

        val classpathProjectDir = projectDir.resolve("build/jugg/classpath/root/project")
        val classpathAppDir = classpathProjectDir.resolve("app").also { it.mkdirs() }
        manager.setCompileContext(
            CompileContextInfo(
                apkInfos = emptyList(),
                moduleBuildPathInfos = mapOf(
                    ideModule.name to ModuleBuildPathInfo(
                        classpathProjectDir,
                        classpathAppDir,
                        ModuleInfo.DEFAULT_BUILD_VARIANT,
                        buildDirRelativePath = "build/app",
                    )
                ),
            )
        )

        manager.updateCustomClasspath(
            listOf(
                ModuleCustomConfig(
                    moduleStdPath = "app",
                    customClasspath = emptyList(),
                    customSyncFilePath = listOf("build/generated/source"),
                    isDoNotIgnored = true,
                )
            )
        )

        val buildPathInfo = manager.compileContext.modules.getValue("app").buildPathInfo
        assertEquals(classpathProjectDir, buildPathInfo.projectRootDir)
        assertEquals(classpathAppDir, buildPathInfo.moduleRootDir)
        assertEquals("build/app", buildPathInfo.buildDirRelativePath)
        assertEquals(listOf("build/generated/source"), buildPathInfo.customSyncFilePath)
    }

    @Test
    fun triggerMerge_keepsExistingCompileContextUntilCallerRebinds() {
        TestGlobal.init()
        val projectDir = temporaryFolder.newFolder("merge")
        val appDir = projectDir.resolve("app").also { it.mkdirs() }
        val initialModule = createModule(projectDir, appDir)
        val mergedModule = initialModule.copy(sourceDirs = listOf(File(appDir, "src/merged/java")))
        val source = mock<IProjectModelSource>()
        whenever(source.load(eq(ProjectModelLoadReason.INITIALIZE), any<BuildTarget>())).thenReturn(ProjectModelResult(JuggProjectInfo(mapOf(initialModule.name to initialModule), agpR8Classpath = null), true))
        whenever(source.load(eq(ProjectModelLoadReason.MERGE), any<BuildTarget>())).thenReturn(ProjectModelResult(JuggProjectInfo(mapOf(mergedModule.name to mergedModule), agpR8Classpath = null), true, isFixMissingOrDelete = true))
        val manager = createManager(JuggPathManager(projectDir), source)
        val initialSourceDirs = manager.compileContext.modules.getValue("app").sourceDirs

        val isFixed = manager.triggerMerge()

        assertEquals(true, isFixed)
        assertEquals(mergedModule.sourceDirs, manager.getProjectInfo().modules.getValue("app").sourceDirs)
        assertEquals(initialSourceDirs, manager.compileContext.modules.getValue("app").sourceDirs)
    }

    @Test
    fun compileContext_readsEnvironmentWhenContextIsCreated() {
        TestGlobal.init()
        val projectDir = temporaryFolder.newFolder("environment")
        val appDir = projectDir.resolve("app").also { it.mkdirs() }
        val module = createModule(projectDir, appDir)
        val source = mock<IProjectModelSource>()
        whenever(source.load(eq(ProjectModelLoadReason.INITIALIZE), any<BuildTarget>())).thenReturn(ProjectModelResult(JuggProjectInfo(mapOf(module.name to module), agpR8Classpath = null), true))
        val environmentSource = RecordingCompileEnvironmentSource(testAndroidHome, listOf("JAVA_HOME=/first"))
        val manager = createManager(JuggPathManager(projectDir), source, environmentSource)

        assertEquals(0, environmentSource.androidHomeReadCount)
        assertEquals(0, environmentSource.compileEnvReadCount)
        environmentSource.compileEnv = listOf("JAVA_HOME=/second")

        assertEquals(listOf("JAVA_HOME=/second"), manager.compileContext.cmdCompileEnv)
        assertEquals(1, environmentSource.androidHomeReadCount)
        assertEquals(1, environmentSource.compileEnvReadCount)
    }

    private fun createManager(pathManager: JuggPathManager): CompileContextManager {
        return createManager(pathManager, GradleProjectModelSource(pathManager, TestGlobal.logger))
    }

    private fun createManager(
        pathManager: JuggPathManager,
        projectModelSource: IProjectModelSource,
        compileEnvironmentSource: ICompileEnvironmentSource = CompileEnvironmentSource(testAndroidHome, emptyList()),
    ): CompileContextManager {
        return CompileContextManager(
            pathManager = pathManager,
            projectModelSource = projectModelSource,
            deployFileManager = mock<DeployFileManager>(),
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            compileEnvironmentSource = compileEnvironmentSource,
            scene = ICompileContext.Scene.IDE,
            logger = TestGlobal.logger,
        )
    }

    private class RecordingCompileEnvironmentSource(
        var androidHome: File?,
        var compileEnv: List<String>,
    ) : ICompileEnvironmentSource {
        var androidHomeReadCount = 0
        var compileEnvReadCount = 0

        override fun getAndroidHome(logger: com.intellij.openapi.diagnostic.Logger): File? {
            androidHomeReadCount++
            return androidHome
        }

        override fun buildCompileEnv(logger: com.intellij.openapi.diagnostic.Logger): List<String> {
            compileEnvReadCount++
            return compileEnv
        }
    }

    private fun createModule(projectDir: java.io.File, appDir: java.io.File): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = projectDir,
            moduleRootDir = appDir,
            manifestFile = appDir.resolve("src/main/AndroidManifest.xml"),
            buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT,
            compileVersion = "30",
            minSdkVersion = "21",
            buildToolsVersion = "30.0.3",
            kotlinJvmTarget = "1.8",
            javaSourceCompatibility = "1.8",
            javaTargetCompatibility = "1.8",
            buildPathInfo = ModuleBuildPathInfo(
                projectDir,
                appDir,
                ModuleInfo.DEFAULT_BUILD_VARIANT,
                buildDirRelativePath = "",
            ),
        )
    }
}
