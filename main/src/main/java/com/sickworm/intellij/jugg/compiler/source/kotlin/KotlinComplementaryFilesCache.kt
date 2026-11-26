package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.script.camelCompat
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import java.io.File

/** Reads expect/actual complementary files from the selected Kotlin Gradle incremental cache. */
class KotlinComplementaryFilesCache(
    private val compiler: K2JVMCompilerIsolate,
) {

    fun read(buildPathInfo: ModuleBuildPathInfo, sourceFiles: List<File>, logger: Logger): List<File> {
        if (sourceFiles.isEmpty()) return emptyList()
        val cacheRoot = findCacheRoot(buildPathInfo)
        if (cacheRoot == null) {
            logger.debug("Kotlin complementary cache is missing or ambiguous for ${buildPathInfo.moduleRootDir}")
            return emptyList()
        }
        return try {
            val result = compiler.readComplementaryFiles(
                cacheRoot,
                buildPathInfo.projectRootDir,
                buildPathInfo.kotlinClassPath,
                sourceFiles,
            ).filter(File::exists).distinctBy { it.canonicalPath }
            if (result.isEmpty()) {
                logger.debug("Kotlin complementary cache has no files for $sourceFiles")
            }
            result
        } catch (e: Throwable) {
            logger.debug("Read Kotlin complementary cache failed for $sourceFiles", e)
            emptyList()
        }
    }

    fun readOutputs(buildPathInfo: ModuleBuildPathInfo, sourceFiles: List<File>, logger: Logger): List<File> {
        if (sourceFiles.isEmpty()) return emptyList()
        val cacheRoot = findCacheRoot(buildPathInfo) ?: return emptyList()
        return try {
            compiler.readSourceOutputs(
                cacheRoot,
                buildPathInfo.projectRootDir,
                buildPathInfo.kotlinClassPath,
                sourceFiles,
            )
        } catch (e: Throwable) {
            logger.debug("Read Kotlin source outputs failed for $sourceFiles", e)
            emptyList()
        }
    }

    internal fun update(
        buildPathInfo: ModuleBuildPathInfo,
        dirtyFiles: List<File>,
        tracking: K2JVMCompilerIsolate.ExpectActualTrackingResult,
        logger: Logger,
    ) {
        if (dirtyFiles.isEmpty()) return
        val cacheRoot = findCacheRoot(buildPathInfo)
        if (cacheRoot == null) {
            logger.debug("Kotlin complementary cache is missing or ambiguous for ${buildPathInfo.moduleRootDir}")
            return
        }
        try {
            compiler.updateComplementaryFiles(
                cacheRoot,
                buildPathInfo.projectRootDir,
                buildPathInfo.kotlinClassPath,
                dirtyFiles,
                tracking,
            )
            logger.debug("Updated Kotlin complementary cache for $dirtyFiles")
        } catch (e: Throwable) {
            logger.debug("Update Kotlin complementary cache failed for $dirtyFiles", e)
        }
    }

    companion object {
        fun findCacheRoot(buildPathInfo: ModuleBuildPathInfo): File? {
            val variant = buildPathInfo.buildVariant.camelCompat
            return listOf(
                "compile${variant}Kotlin",
                "compile${variant}KotlinAndroid",
            ).map { taskName ->
                File(buildPathInfo.buildDir, "kotlin/$taskName/cacheable/caches-jvm/jvm")
            }.filter {
                File(it, "kotlin/complementary-files.tab").exists()
            }.singleOrNull()
        }
    }
}
