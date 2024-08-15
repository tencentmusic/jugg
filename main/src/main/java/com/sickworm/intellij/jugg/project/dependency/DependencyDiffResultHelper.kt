package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

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
        diffResultWithFull.updatedLibraries.forEach {
            val newJar = it.dependency?.libraries?.find(LibraryDependency::isJar)
            val oldJar = it.oldDependency?.libraries?.find(LibraryDependency::isJar)
            if (newJar != null && oldJar != null && newJar.file.path != oldJar.file.path) {
                relativeOldJar[newJar.file.absolutePath] = oldJar.file
            }
        }

        // relative path to old file except jar file
        val relativeOldFiles: MutableMap<String, File> = mutableMapOf()
        diffResult.updatedLibraries.forEach { library ->
            library.dependency?.libraries?.forEach { new ->
                val old = library.oldDependency?.libraries?.find { new.type == it.type }
                if (old != null && new.file.path != old.file.path) {
                    relativeOldFiles[new.file.absolutePath] = old.file
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
        changedFiles.toList().forEach {
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
                    ).withDependencyName(it.dependencyName)
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
                    ).withDependencyName(it.dependencyName)
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