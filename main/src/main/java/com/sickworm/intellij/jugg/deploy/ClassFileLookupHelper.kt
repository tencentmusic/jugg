package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.util.zip.ZipFile

/**
 * Shared class-file lookup helper for deploy/apt flows.
 *
 * It searches class directories first, then falls back to jar entries.
 */
object ClassFileLookupHelper {
    data class ClassFileLookupResult(
        val file: File,
        val baseDir: File,
        val module: ModuleInfo,
    )

    fun findClassFilesByName(
        classNames: List<String>,
        dependModules: List<ModuleInfo>,
        dependLibraries: List<File>,
        tempDir: File,
        logger: Logger,
    ): List<ClassFileLookupResult> {
        if (classNames.isEmpty()) {
            logger.debug("findClassFilesByName: no class files")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()
        val classRelativePaths = classNames
            .asSequence()
            .map { it.classNameToPath }
            .filter { !it.contains("\$ExternalSyntheticLambda") }
            .distinct()
            .toMutableList()
        logger.debug("findClassFilesByName: classRelativePaths $classRelativePaths")

        val foundClassesFiles = mutableListOf<ClassFileLookupResult>()
        dependModules.forEach { moduleInfo ->
            for (classPath in moduleInfo.buildPathInfo.allClassPath) {
                if (!classPath.isDirectory) {
                    continue
                }
                val iterator = classRelativePaths.iterator()
                while (iterator.hasNext()) {
                    val relativePath = iterator.next()
                    val destFile = File(classPath, relativePath)
                    if (!destFile.exists()) {
                        continue
                    }
                    iterator.remove()
                    foundClassesFiles += ClassFileLookupResult(
                        file = destFile,
                        baseDir = classPath,
                        module = moduleInfo,
                    )
                }
            }
        }
        if (classRelativePaths.isEmpty()) {
            logger.debug("findClassFilesByName cost: ${System.currentTimeMillis() - startTime} ms")
            return foundClassesFiles
        }

        logger.debug("findClassFilesByName: libraryPaths ${dependLibraries.size}")
        dependLibraries.forEach libraryLoop@{ libraryFile ->
            if (!libraryFile.isFile || libraryFile.extension != "jar") {
                return@libraryLoop
            }
            val iterator = classRelativePaths.iterator()
            while (iterator.hasNext()) {
                val relativePath = iterator.next()
                try {
                    if (!libraryFile.exists()) {
                        continue
                    }
                    ZipFile(libraryFile).use { zipFile ->
                        val entry = zipFile.getEntry(relativePath) ?: return@use
                        val destFile = File(tempDir, relativePath)
                        destFile.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { inputStream ->
                            destFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        iterator.remove()
                        foundClassesFiles += ClassFileLookupResult(
                            file = destFile,
                            baseDir = tempDir,
                            module = ModuleInfo.virtualModule,
                        )
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "findClassFilesByName: failed in library ${libraryFile.absolutePath}/${relativePath}, error: ${e.message}",
                    )
                }
            }
        }

        if (classRelativePaths.isNotEmpty()) {
            logger.debug("findClassFilesByName: failed to find class files: $classRelativePaths")
        }
        logger.debug("findClassFilesByName cost: ${System.currentTimeMillis() - startTime} ms")
        return foundClassesFiles
    }
}
