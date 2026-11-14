package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException

/** Keeps empty generated Java placeholders out of Kotlin's directory-based class lookup. */
internal fun filterEmptyGeneratedJavaSources(dirs: List<File>, copyRoot: File, logger: Logger): List<File> {
    val started = System.nanoTime()
    try {
        return dirs.mapIndexed { index, dir ->
            try {
                filterGeneratedSourceRoot(dir, File(copyRoot, index.toString()), logger)
            } catch (e: IOException) {
                logger.warn("Failed to filter generated sources: root=$dir; using the original source root", e)
                dir
            } catch (e: SecurityException) {
                logger.warn("Cannot access generated sources: root=$dir; using the original source root", e)
                dir
            }
        }
    } finally {
        logger.debug("Generated source paths filtering: roots=${dirs.size}, " +
                "cost=${(System.nanoTime() - started) / 1_000_000}ms")
    }
}

private fun filterGeneratedSourceRoot(dir: File, destination: File, logger: Logger): File {
    val files = dir.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile }.toList()
    val emptyJavaFiles = files.filter { it.extension == "java" && it.length() == 0L }.toSet()
    if (emptyJavaFiles.isEmpty()) return dir

    if (destination.exists() && !destination.deleteRecursively()) {
        logger.warn("Failed to clear generated source copy: $destination; using the original source root: $dir")
        return dir
    }
    if (!destination.mkdirs()) {
        logger.warn("Failed to create generated source copy: $destination; using the original source root: $dir")
        return dir
    }
    files.filterNot { it in emptyJavaFiles }.forEach { file ->
        file.copyTo(File(destination, file.relativeTo(dir).path), overwrite = true)
    }
    logger.debug("Filtered empty generated Java sources: root=$dir, " +
            "files=$emptyJavaFiles, copy=$destination")
    return destination
}
