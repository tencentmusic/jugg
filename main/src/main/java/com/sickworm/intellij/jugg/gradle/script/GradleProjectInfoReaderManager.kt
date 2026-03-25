package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.*
import org.gradle.api.Project
import org.gradle.api.initialization.IncludedBuild
import org.gradle.util.GradleVersion
import java.io.File

/**
 * Implementation of readProjectInfo.gradle.kts
 */
@Suppress("unused")
class GradleProjectInfoReaderManager(
    private val rootProject: Project,
    private val includeBuildProjects: Collection<IncludedBuild>,
) {

    // Prefer jugg.projectDir property to support projects where Gradle root != IDE project dir
    // (e.g., kugou_like/ project with android/ as Gradle root)
    private val juggPathManager = JuggPathManager(
        rootProject.properties["jugg.projectDir"]?.toString()?.let { File(it) }
            ?: rootProject.rootDir
    )

    fun readAndSave() {
        try {
            val isDiffMode = rootProject.properties[PARAM_DIFF_MODE] == "true"
            println("Jugg: readProjectInfo.gradle execute start, diffMode: $isDiffMode, " +
                    "includeBuildProjects: ${includeBuildProjects.map { it.projectDir }}")
            readEnvironment()
            val startTime = System.currentTimeMillis()
            val lastProjectInfo = readLastProjectInfo()
            val projectInfo = GradleProjectInfoReader(rootProject, lastProjectInfo, juggPathManager.projectDir).getProjectInfo()

            if (isDiffMode) {
                GradleDependencyDiffer(rootProject, projectInfo, juggPathManager.projectDir).outputDiffToDir()
            } else {
                writeProjectInfoFile(projectInfo)
                writeIncludeProjectsFile()
                GradleDependencyDiffer(rootProject, projectInfo, juggPathManager.projectDir).deleteTmpProjectInfos()
            }

            val costTime = System.currentTimeMillis() - startTime
            println("Jugg: readProjectInfo.gradle execute success, cost: ${costTime}ms")
        } catch (e: Throwable) {
            println("Jugg: readProjectInfo.gradle execute failed: $e")
            printException(e)
        }
    }

    private fun readEnvironment() {
        try {
            val gradleVersion = GradleVersion.current()
            val agpVersion = checkAgpVersion(rootProject)
            println("Jugg: readEnvironment gradleVersion: $gradleVersion, agpVersion: $agpVersion")
        } catch (e: Throwable) {
            println("Jugg: readProjectInfo.gradle readEnvironment failed: $e")
            printException(e)
        }
    }

    private fun checkAgpVersion(rootProject: Project): String {
        val project = rootProject.subprojects.find { it.plugins.hasPlugin("com.android.application") }
        if (project == null) return "no_application_module"
        try {
            val plugin = project.plugins.findPlugin("com.android.base")
                ?: return "no_plugin"
            val versionClass = try {
                plugin::class.java.classLoader.loadClass("com.android.Version")
            } catch (exception: ClassNotFoundException) {
                plugin::class.java.classLoader.loadClass("com.android.builder.model.Version")
            } catch (ex: ClassNotFoundException) {
                return "no_version_class"
            }
            val field = versionClass.fields.find { it.name == "ANDROID_GRADLE_PLUGIN_VERSION" }
                ?: return "no_version_field"
            return field.get(null) as String
        } catch (ex: Throwable) {
            return "throwable_$ex"
        }
    }

    /**
     * We need this to determined build variant, the info is from IDE
     */
    private fun readLastProjectInfo(): JuggProjectInfoSerialize?  {
        var lastProjectInfo: File? = null

        if (juggPathManager.gradleProjectInfoFile.exists()) {
            lastProjectInfo = juggPathManager.gradleProjectInfoFile
        }
        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo ${juggPathManager.gradleProjectInfoFile} not exists")
            return null
        }

        val lastProjectInfoSerialize = ProjectInfoSerializerInGradle(lastProjectInfo).load()
        if (lastProjectInfoSerialize == null) {
            println("Jugg: lastProjectInfo ${juggPathManager.gradleProjectInfoFile} load failed")
            return null
        }
        return lastProjectInfoSerialize
    }

    private fun writeProjectInfoFile(projectInfo: JuggProjectInfo) {
        ProjectInfoSerializerInGradle(juggPathManager.gradleProjectInfoFile).save(projectInfo)
    }

    private fun writeIncludeProjectsFile() {
        val includeProjectsFile = juggPathManager.gradleIncludeBuildsFile
        if (includeBuildProjects.isEmpty()) {
            if (includeProjectsFile.exists()) includeProjectsFile.delete()
        } else {
            val projectFiles = includeBuildProjects.mapIndexed { index, it ->
                val originFile = JuggPathManager(it.projectDir).gradleProjectInfoFile
                val targetFile = File(includeProjectsFile.parentFile, "include_build_${index + 1}_gradle_project_infos.json")
                originFile.copyTo(targetFile, true)
                targetFile
            }.joinToString("\n")
            includeProjectsFile.parentFile.mkdirs()
            includeProjectsFile.writeText(projectFiles)
        }
    }

    companion object {
        const val PARAM_DIFF_MODE = "jugg.diffMode"
        const val PARAM_INC_DEPLOY_TIMES = "jugg.incDeployTimes"
    }
}