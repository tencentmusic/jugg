package com.sickworm.intellij.jugg

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import java.io.File

/**
 * CopyGeneratedSourceHelper synchronizes fetched remote generated outputs
 * (including custom sync files and build/generated) back to local module build directories.
 */
class CopyGeneratedSourceHelper(
    private val taskRunnerManager: TaskRunnerManager,
    private val logger: Logger,
) {

    fun copy(modules: Map<String, ModuleInfo>) {
        logger.info("copyGeneratedSourceToLocal")
        taskRunnerManager.runTaskSafe("Copy Generated Source to local", {
            calculateSyncToLocalPaths(modules.values).forEach { (fileOrDirInClasspath, fileOrDirInLocal) ->
                logger.debug("Copy generated source from $fileOrDirInClasspath to $fileOrDirInLocal")
                if (!fileOrDirInClasspath.exists()) {
                    logger.debug("Skip copy, $fileOrDirInClasspath not exists")
                    return@forEach
                }
                if (fileOrDirInClasspath.path.equals(fileOrDirInLocal.path)) {
                    logger.debug("Skip copy, source and target are the same")
                    return@forEach
                }
                if (fileOrDirInLocal.exists() && !fileOrDirInLocal.isDirectory) {
                    fileOrDirInLocal.delete()
                }
                fileOrDirInClasspath.copyRecursively(fileOrDirInLocal, overwrite = true)
            }
        }, isBlockIncrementalCompile = false)
    }
}

internal fun calculateSyncToLocalPaths(modules: Collection<ModuleInfo>): List<Pair<File, File>> {
    return modules.flatMap { module ->
        val localBuildDir = ModuleBuildPathInfo(
            module.projectRootDir,
            module.moduleRootDir,
            module.buildVariant,
            buildDirRelativePath = module.buildPathInfo.buildDirRelativePath,
        ).buildDir
        module.buildPathInfo.syncToLocalPathList.map { fileOrDirInClasspath ->
            val isBuildOutput = fileOrDirInClasspath.toPath().startsWith(module.buildPathInfo.buildDir.toPath())
            val sourceBaseDir = if (isBuildOutput) module.buildPathInfo.buildDir else module.buildPathInfo.moduleRootDir
            val targetBaseDir = if (isBuildOutput) localBuildDir else module.moduleRootDir
            fileOrDirInClasspath to fileOrDirInClasspath.changeBaseDir(sourceBaseDir, targetBaseDir)
        }
    }
}
