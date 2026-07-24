package com.sickworm.intellij.jugg.project

import com.intellij.openapi.module.ModuleManager
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.server.protocols.ModuleCustomConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
        ProjectInfoSerializer(pathManager.ideProjectInfoFile, TestGlobal.logger)
            .save(JuggProjectInfo(mapOf(ideModule.name to ideModule)))
        val manager = createManager(projectDir, pathManager)

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

    private fun createManager(projectDir: java.io.File, pathManager: JuggPathManager): CompileContextManager {
        val moduleManager = mock<ModuleManager>()
        whenever(moduleManager.modules).thenReturn(emptyArray())
        val project = JuggMockProject(projectDir)
        project.registerService(ModuleManager::class.java, moduleManager)
        JuggLogger.register(project, pathManager.logDir)
        return CompileContextManager(
            project = project,
            pathManager = pathManager,
            deployFileManager = mock<DeployFileManager>(),
            deployHisManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            moduleManager = moduleManager,
            logger = TestGlobal.logger,
        )
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
