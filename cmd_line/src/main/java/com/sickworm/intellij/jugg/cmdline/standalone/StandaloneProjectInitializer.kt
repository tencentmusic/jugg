package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.mcp.ProjectInitializationResult
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.context.ICompileEnvironmentSource
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.gradle.compile.GradleScriptWriter
import com.sickworm.intellij.jugg.project.info.GradleProjectModelSource
import com.sickworm.intellij.jugg.project.info.ProjectModelLoadReason
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationGenerator
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationStore
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager

/** Creates the first standalone profile and bootstraps Gradle project information when absent. */
class StandaloneProjectInitializer(
    private val pathManager: JuggPathManager,
    private val compileEnvironmentSource: ICompileEnvironmentSource,
    private val logger: Logger,
) {
    private val store = CliRunConfigurationStore(pathManager)

    fun initialize(): ProjectInitializationResult {
        store.loadCurrent()?.let {
            if (it.isRemoteCompile) return ProjectInitializationResult(false, REMOTE_COMPILE_UNSUPPORTED)
            return it.toResult("Standalone project is already initialized.")
        }
        if (!pathManager.gradleProjectInfoFile.isFile && !fetchProjectInfo()) {
            return ProjectInitializationResult(false, "Unable to read Gradle project information. Check the compile log for details.")
        }
        val projectInfo = GradleProjectModelSource(pathManager, logger)
            .load(ProjectModelLoadReason.INITIALIZE, BuildTarget.APP).projectInfo
            ?: return ProjectInitializationResult(false, "No Android application module was found in Gradle project information.")
        return runCatching {
            val configuration = CliRunConfigurationGenerator.generate(projectInfo)
            store.save(configuration)
            store.select(configuration.id)
            configuration.toResult("Standalone project initialized successfully.")
        }.getOrElse { ProjectInitializationResult(false, it.message ?: "Standalone project initialization failed.") }
    }

    private fun fetchProjectInfo(): Boolean {
        GradleScriptWriter(pathManager, logger).writeInitGradleFile()
        val wrapper = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "gradlew.bat" else "./gradlew"
        val command = CompileProjectCommand(
            "$wrapper assembleDebug --dry-run --no-daemon",
            pathManager.projectDir.path,
            pathManager.initGradleFilePath.path,
            logger = logger,
        )
        return CmdExecutor(logger, isLogAllDebug = true)
            .invoke(command, compileEnvironmentSource.buildCompileEnv(logger)) == 0 && pathManager.gradleProjectInfoFile.isFile
    }

    private fun com.sickworm.intellij.jugg.project.runtime.CliRunConfiguration.toResult(message: String) =
        ProjectInitializationResult(true, message, id, name, compileCommand)

    companion object {
        const val REMOTE_COMPILE_UNSUPPORTED =
            "Standalone Runtime does not support remote compile profiles. Use IDEA or select a local profile."
    }
}
