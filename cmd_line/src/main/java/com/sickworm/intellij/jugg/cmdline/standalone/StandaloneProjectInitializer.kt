package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
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

    internal fun initialize(): StandaloneProjectInitializationResult {
        store.loadCurrent()?.let { return it.toResult("Standalone project is already initialized.") }
        if (!pathManager.gradleProjectInfoFile.isFile && !fetchProjectInfo()) {
            return StandaloneProjectInitializationResult(
                false,
                "Unable to read Gradle project information. Check the compile log for details.",
            )
        }
        val projectInfo = GradleProjectModelSource(pathManager, logger)
            .load(ProjectModelLoadReason.INITIALIZE, BuildTarget.APP).projectInfo
            ?: return StandaloneProjectInitializationResult(
                false,
                "No Android application module was found in Gradle project information.",
            )
        return runCatching {
            val configuration = CliRunConfigurationGenerator.generate(projectInfo)
            store.save(configuration)
            store.select(configuration.id)
            configuration.toResult("Standalone project initialized successfully.")
        }.getOrElse {
            StandaloneProjectInitializationResult(false, it.message ?: "Standalone project initialization failed.")
        }
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
        StandaloneProjectInitializationResult(true, message)
}

/** Describes the result of preparing a standalone compile profile on demand. */
internal data class StandaloneProjectInitializationResult(
    val isSuccess: Boolean,
    val message: String,
)
