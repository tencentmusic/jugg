package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.dependencyName
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.oldRes
import com.sickworm.intellij.jugg.gradle.compile.crc32
import java.io.File

/**
 * DirToFileMapHelper expands changed resource directories into file-level maps with old/new filtering.
 */
object DirToFileMapHelper {

    fun createDirToResFileMap(compileFiles: List<CompileFile>, logger: Logger): Map<File, List<File>> {
        return compileFiles
            .filter { it.file.isDirectory }
            .associate { compileFile ->
                val allResFiles = compileFile.file.listFilesRecursively()
                val relativeOldResDirectory = compileFile.oldRes
                val relativeOldFiles = relativeOldResDirectory?.listFilesRecursively()
                if (relativeOldResDirectory == null || relativeOldFiles.isNullOrEmpty()) {
                    logger.debug("${compileFile.dependencyName} has none relative old res files")
                    return@associate compileFile.file to allResFiles
                } else {
                    // filter no changed files
                    logger.debug("${compileFile.dependencyName}[${compileFile.relativeFile}] has relative old files: ${compileFile.oldRes}")
                    val checksumMap = relativeOldFiles.associate {
                        it.relativeTo(relativeOldResDirectory).path to it.crc32
                    }
                    val filteredResFiles = allResFiles.filter {
                        val relativePath = it.relativeTo(compileFile.file).path
                        val oldChecksum = checksumMap[relativePath] ?: return@filter true
                        return@filter it.crc32 != oldChecksum
                    }
                    logger.debug("${compileFile.dependencyName} full files: ${allResFiles.size}, " +
                            "remain files after filtered: ${filteredResFiles.size}")
                    return@associate compileFile.file to filteredResFiles
                }
            }
    }
}
