package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Translates dependency-diff results into compile-ready changed/removed file sets.
 */
class DependencyDiffResultHelper(
    private val logger: Logger,
    private val tempModule: ModuleInfo,
    private val diffResult: DependencyDiffResult,
    private val diffResultWithFull: DependencyDiffResult,
) {

    fun getNewLibraryFiles(): List<ChangedFile> {
        logger.debug("get new libraries: ${diffResult.newLibraryDependencies}")

        // relative path to old jar file
        // diff with full build dependencies, because library dex are in one file, which can not incremental update
        val relativeOldJar: MutableMap<String, File> = mutableMapOf()
        diffResultWithFull.updatedLibraries.forEach updatedLibrary@{ updatedLibrary ->
            updatedLibrary.dependency?.libraries?.forEach library@{ library ->
                val newJar = library.takeIf { it.isJar } ?: return@library
                val oldJar = updatedLibrary.oldDependency?.libraries?.find { newJar.isRelativeOldDependencyJar(it) }
                if (oldJar == null) {
                    logger.debug("$library has no relative old jar")
                    return@library
                }
                if (newJar.file.path == oldJar.file.path) {
                    logger.debug("new jar file is same with old, skip: ${library.name} -> ${oldJar.file}")
                    return@library
                }
                if (!oldJar.file.exists()) {
                    logger.debug("old jar file not found, it may be deleted by gradle: ${oldJar.name} -> ${oldJar.file}")
                    return@library
                }
                logger.debug("${newJar.file} has relative files: ${oldJar.file}")
                relativeOldJar[newJar.file.absolutePath] = oldJar.file
            }
        }

        // relative path to old file except jar file
        val relativeOldFiles: MutableMap<String, File> = mutableMapOf()
        diffResult.updatedLibraries.forEach { library ->
            library.dependency?.libraries?.forEach { new ->
                val old = library.oldDependency?.libraries?.find { new.type == it.type }
                if (old != null && new.file.path != old.file.path && old.file.exists()) {
                    if (!old.file.exists()) {
                        logger.debug("old file not found, it may be deleted by gradle: ${old.name} -> ${old.file}")
                    } else {
                        relativeOldFiles[new.file.absolutePath] = old.file
                    }
                }

                // Guess assets and lib dir. (info is missing from Idea. through gradle is able to get it, but I want to guess it all)
                val parentFile = new.file.parentFile
                val oldParentFile = old?.file?.parentFile
                if (parentFile != null && oldParentFile != null && parentFile.path != oldParentFile.path) {
                    val assetDir = File(parentFile, "assets")
                    val oldAssetDir = File(oldParentFile, "assets")
                    if (assetDir.exists() && oldAssetDir.exists()) {
                        relativeOldFiles[assetDir.absolutePath] = oldAssetDir
                    }

                    val libDir = File(parentFile, "jni")
                    val oldLibDir = File(oldParentFile, "jni")
                    if (libDir.exists() && oldLibDir.exists()) {
                        relativeOldFiles[libDir.absolutePath] = oldLibDir
                    }
                }
            }
        }

        val revertLibraries = getRevertLibraryFiles()
        val changedFiles = diffResult.newLibraryDependencies.mapNotNull {
            val isRevertLibrary = revertLibraries.any { revert -> revert.file.absolutePath == it.file.absolutePath }
            if (isRevertLibrary) {
                logger.debug("skip revert library: ${it.file.absolutePath}")
                return@mapNotNull null
            }

            if (it.isAndroidManifest) {
                return@mapNotNull ChangedFile(
                    type = CompileFile.Type.AndroidManifest,
                    file = it.file,
                    baseDir = it.file,
                    module = tempModule,
                ).withDependencyName(it.name)
                    .withOldManifest(relativeOldFiles[it.file.absolutePath])
            } else if (it.isRes) {
                return@mapNotNull ChangedFile(
                    type = CompileFile.Type.Resource,
                    file = it.file,
                    baseDir = it.file,
                    module = tempModule,
                ).withDependencyName(it.name)
                    .withOldRes(relativeOldFiles[it.file.absolutePath])
            } else if (it.isJar) {
                return@mapNotNull ChangedFile(
                    type = CompileFile.Type.Class,
                    file = it.file,
                    baseDir = it.file.parentFile!!,
                    module = tempModule,
                ).withDependencyName(it.name)
                    .withOldJar(relativeOldJar[it.file.absolutePath])
            }

            logger.debug("skip unknown type library: $it")
            return@mapNotNull null
        }.toMutableList()

        // Guess assets and lib dir. (info is missing from Idea. through gradle is able to get it, but I want to guess it all)
        val guessedDirs: MutableSet<File> = mutableSetOf()
        diffResult.newLibraryDependencies.forEach {
            val parentFile = it.file.parentFile ?: return@forEach
            val assetDir = File(parentFile, "assets")
            if (!guessedDirs.contains(assetDir) && assetDir.exists() && assetDir.isDirectory && assetDir.listFiles()?.isNotEmpty() == true) {
                guessedDirs.add(assetDir)
                changedFiles.add(
                    ChangedFile(
                        type = CompileFile.Type.Asset,
                        file = assetDir,
                        baseDir = assetDir,
                        module = tempModule,
                    ).withDependencyName(it.name)
                        .withOldRes(relativeOldFiles[assetDir.absolutePath])
                )
            }
            val libDir = File(parentFile, "jni")
            if (!guessedDirs.contains(libDir) && libDir.exists() && libDir.isDirectory && libDir.listFiles()?.isNotEmpty() == true) {
                guessedDirs.add(libDir)
                changedFiles.add(
                    ChangedFile(
                        type = CompileFile.Type.NativeLib,
                        file = libDir,
                        baseDir = libDir,
                        module = tempModule,
                    ).withDependencyName(it.name)
                        .withOldRes(relativeOldFiles[libDir.absolutePath])
                )
            }
        }


        logger.debug("changed files: $changedFiles")
        return changedFiles
    }

    fun getRemovedLibraryFiles(): List<ChangedFile> {
        logger.debug("get removed libraries: ${diffResult.removedLibraryDependencies}")

        val removedLibraryFiles = mutableListOf<ChangedFile>()
        // delete removed library
        diffResult.removedLibraryDependencies.forEach {
            if (!it.isJar) {
                return@forEach
            }
            val changedFile = ChangedFile(
                type = CompileFile.Type.Class,
                file = it.file,
                baseDir = it.file.parentFile!!,
                module = tempModule,
            ).withDependencyName(it.name)
            removedLibraryFiles.add(changedFile)
        }

        // delete reverted library
        removedLibraryFiles.addAll(getRevertLibraryFiles())

        logger.debug("removed library files: $removedLibraryFiles")
        return removedLibraryFiles
    }

    private fun getRevertLibraryFiles(): List<ChangedFile> {
        val revertLibraries = mutableListOf<ChangedFile>()
        diffResult.newLibraryDependencies.forEach { newDependency ->
            val fullDiff = diffResultWithFull.newLibraryDependencies.find { newDependency.name == it.name }
            if (fullDiff == null) {
                // not exists in diffResultWithFull, which means it is a reverted library
                val changedFile = ChangedFile(
                    type = CompileFile.Type.Class,
                    file = newDependency.file,
                    baseDir = newDependency.file.parentFile!!,
                    module = tempModule,
                ).withDependencyName(newDependency.name)
                revertLibraries.add(changedFile)
            }
        }

        logger.debug("revert libraryFiles: $revertLibraries")
        return revertLibraries
    }
}
