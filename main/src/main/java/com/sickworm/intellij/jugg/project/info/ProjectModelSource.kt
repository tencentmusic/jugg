package com.sickworm.intellij.jugg.project.info

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File

/** Provides project model data for IDEA or standalone runtime. */
interface IProjectModelSource {
    fun load(reason: ProjectModelLoadReason, buildTarget: BuildTarget): ProjectModelResult
}

/** Identifies why a runtime reloads its project model. */
enum class ProjectModelLoadReason {
    INITIALIZE,
    HOST_SYNC,
    HOST_FULL_BUILD_FALLBACK,
    VALIDATE,
    GRADLE_FETCH,
    MERGE,
}

/** Carries the effective model and source-specific reload facts. */
data class ProjectModelResult(
    val projectInfo: JuggProjectInfo?,
    val isModelReloaded: Boolean,
    val needsGradleRefresh: Boolean = false,
    val isFixMissingOrDelete: Boolean = false,
    val includedBuildModuleRoots: Set<File> = emptySet(),
)

/** Loads a standalone project model exclusively from Gradle snapshots. */
class GradleProjectModelSource(
    private val pathManager: JuggPathManager,
    private val logger: Logger,
) : IProjectModelSource {

    override fun load(reason: ProjectModelLoadReason, buildTarget: BuildTarget): ProjectModelResult {
        val projectInfoSnapshots = createGradleProjectInfoSerializers(pathManager, logger).map { it.load() }
        val projectInfos = projectInfoSnapshots.filterNotNull()
        val modules = linkedMapOf<String, ModuleInfo>()
        projectInfos.forEach { projectInfo ->
            projectInfo.modules.values.forEach { module ->
                if (ModulePathMergePolicy.shouldIncludeGradleOnlyModule(module, buildTarget)) {
                    modules.putIfAbsent(module.name, module)
                }
            }
        }
        return ProjectModelResult(
            projectInfo = JuggProjectInfo(modules, projectInfos.mapNotNull { it.agpR8Classpath }.firstOrNull()).takeIf { it.modules.isNotEmpty() },
            isModelReloaded = reason != ProjectModelLoadReason.VALIDATE,
            needsGradleRefresh = projectInfos.isEmpty() || projectInfos.any { it.hasMissingDependencies("gradle", logger) },
            includedBuildModuleRoots = ModulePathMergePolicy.findIncludedBuildModuleRoots(projectInfoSnapshots),
        )
    }
}

/** Creates serializers for the root Gradle snapshot and all included builds. */
fun createGradleProjectInfoSerializers(pathManager: JuggPathManager, logger: Logger): List<ProjectInfoSerializer> {
    val serializers = mutableListOf(ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger))
    if (pathManager.gradleIncludeBuildsFile.exists()) {
        serializers += pathManager.gradleIncludeBuildsFile.readLines().filter { it.isNotBlank() }.map { ProjectInfoSerializer(File(it), logger) }
    }
    return serializers
}

/** Checks whether persisted dependency paths are incomplete or stale. */
fun JuggProjectInfo.hasMissingDependencies(name: String, logger: Logger): Boolean {
    val isMissingMainJarMap = mutableMapOf<String, Boolean>()
    var isMissing = false
    val transformsPath = ".gradle${File.separator}caches${File.separator}transforms"
    val mainJarPath = "${File.separator}jars${File.separator}classes.jar"
    val jarsInAarPath = "${File.separator}jars${File.separator}"
    modules.values.forEach { module ->
        module.libraryDependencies.forEach { dependency ->
            if (!dependency.file.exists()) {
                isMissing = true
                logger.debug("Missing library dependency $dependency, path: ${dependency.file.path}")
            }
            if (dependency.file.path.contains(transformsPath)) {
                if (dependency.file.path.contains(mainJarPath)) {
                    isMissingMainJarMap[dependency.name] = false
                } else if (!dependency.isJar || dependency.file.path.contains(jarsInAarPath)) {
                    isMissingMainJarMap.getOrPut(dependency.name) { true }
                }
            }
        }
    }
    isMissingMainJarMap.forEach { (dependencyName, isMissingJar) ->
        if (isMissingJar) {
            logger.debug("Missing classes.jar $dependencyName")
            isMissing = true
        }
    }
    logger.debug("checkMissing for $name, isMissing: $isMissing")
    return isMissing
}
