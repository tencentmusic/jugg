package com.sickworm.intellij.jugg.deploy.run

import java.io.File

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project

data class SuggestRunConfiguration(
    val moduleName: String,
    val compileCommand: String,
    val outputApkPath: String,
    val variantName: String? = null,
    val runConfigName: String = createRunConfigName(moduleName, variantName),
) {

    val baseRunConfigName: String get() = createRunConfigName(moduleName, null)

    companion object {

        private const val RUN_CONFIG_PREFIX = "jugg:"

        fun getModuleNameByRunConfigName(runConfigName: String): String {
            return runConfigName.substringAfter(RUN_CONFIG_PREFIX).substringBefore(":")
        }

        fun resolveModuleName(ideModuleName: String, rootProjectName: String?): String {
            val moduleName = ideModuleName.removeSuffix(".main")
            return rootProjectName?.let { moduleName.removePrefix("$it.") } ?: moduleName
        }

        /** Resolves the Gradle identity when available and preserves the legacy result on any failure. */
        fun resolveModuleName(module: Module, project: Project): String {
            val fallback = resolveModuleName(module.name, project.name)
            return try {
                val projectDir = project.basePath?.let(::File) ?: return fallback
                val pathClass = Class.forName("com.android.tools.idea.projectsystem.gradle.GradleProjectPathKt")
                val gradleProjectPath = pathClass.getMethod("getGradleProjectPath", Module::class.java)
                    .invoke(null, module) ?: return fallback
                val gradleProjectPathClass = gradleProjectPath.javaClass
                val path = gradleProjectPathClass.getMethod("getPath").invoke(gradleProjectPath) as? String
                    ?: return fallback
                val buildRoot = gradleProjectPathClass.getMethod("getBuildRoot").invoke(gradleProjectPath) as? String
                    ?: return fallback
                resolveGradleModuleName(
                    gradleProjectPath = path,
                    gradleBuildRoot = buildRoot,
                    projectDir = projectDir,
                    externalProjectId = ExternalSystemApiUtil.getExternalProjectId(module),
                ) ?: fallback
            } catch (_: Throwable) {
                fallback
            }
        }

        /** Converts a Gradle project path and build identity into the module name used by Jugg. */
        fun resolveGradleModuleName(
            gradleProjectPath: String,
            gradleBuildRoot: String,
            projectDir: File,
            externalProjectId: String?,
        ): String? {
            val modulePath = gradleProjectPath.trim(':').replace(':', '.').takeIf { it.isNotEmpty() }
                ?: return null
            if (File(gradleBuildRoot).absoluteFile.normalize() == projectDir.absoluteFile.normalize()) {
                return modulePath
            }
            val buildName = externalProjectId?.substringBefore(':')?.takeIf { it.isNotEmpty() }
                ?: File(gradleBuildRoot).name.takeIf { it.isNotEmpty() }
                ?: return null
            return "$buildName.$modulePath"
        }

        fun createCompileCommand(moduleName: String, taskName: String): String {
            return "./gradlew :${moduleName.replace('.', ':')}:$taskName"
        }

        fun createOutputApkPath(
            projectDir: File,
            buildDir: File,
            productFlavorPath: String,
            buildType: String,
        ): String {
            val relativeBuildDir = buildDir.relativeTo(projectDir).invariantSeparatorsPath.trimEnd('/')
            return "$relativeBuildDir/outputs/apk/$productFlavorPath$buildType/*.apk"
        }

        private fun createRunConfigName(moduleName: String, variantName: String?): String {
            return "$RUN_CONFIG_PREFIX$moduleName" + variantName?.let { ":$it" }.orEmpty()
        }

        fun isDefaultRunConfigName(runConfigName: String): Boolean {
            return runConfigName == DEFAULT.runConfigName || runConfigName.startsWith("Unnamed")
        }

        val DEFAULT: SuggestRunConfiguration
            get() = SuggestRunConfiguration(
                moduleName = "app",
                compileCommand = "./gradlew :app:assembleDebug",
                outputApkPath = "app/build/outputs/apk/debug/*.apk",
                variantName = "debug",
                runConfigName = "jugg:default"
            )
    }
}
