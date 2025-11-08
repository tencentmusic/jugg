package com.sickworm.intellij.jugg.cmdline.incremental

import org.apache.log4j.Level
import java.io.File

class ParamsParser {

    fun parse(args: Array<String>): Params {
        val keyValueMap = mutableMapOf<String, String>()
        for (arg in args) {
            val index = arg.indexOf('=')
            if (index == -1) {
                continue
            }
            val key = arg.substring(0, index)
            val value = arg.substring(index + 1)
            keyValueMap[key] = value
        }

        val baseBuildProjectDirValue = keyValueMap["baseBuildJuggRootDir"] ?: ""
        if (baseBuildProjectDirValue.isEmpty()) {
            throw IncrementalException("Param 'baseBuildJuggRootDir' not found.")
        }
        var baseBuildJuggRootDir = File(baseBuildProjectDirValue).normalize()
        if (!baseBuildJuggRootDir.exists()) {
            throw IncrementalException("Param 'baseBuildJuggRootDir' invalid, not exists: $baseBuildProjectDirValue")
        }
        if (!baseBuildJuggRootDir.isAbsolute) {
            baseBuildJuggRootDir = baseBuildJuggRootDir.absoluteFile
        }

        val sourceProjectDirValue = keyValueMap["sourceProjectDir"] ?: baseBuildProjectDirValue
        var sourceProjectDir = File(sourceProjectDirValue).normalize()
        if (!sourceProjectDir.exists()) {
            throw IncrementalException("Param 'sourceProjectDir' invalid, not exists: $sourceProjectDirValue")
        }
        if (!sourceProjectDir.isAbsolute) {
            sourceProjectDir = sourceProjectDir.absoluteFile
        }

        val outputApkDirValue = keyValueMap["outputApkDir"] ?: ""
        if (outputApkDirValue.isEmpty()) {
            throw IncrementalException("Param 'outputApkDir' not found.")
        }
        val outputApkDir = File(outputApkDirValue)

        val changedFilesValue = keyValueMap["changedFiles"] ?: ""
        if (changedFilesValue.isEmpty()) {
            throw IncrementalException("Param 'changedFiles' not found.")
        }
        val changedFiles = changedFilesValue.split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .map { if(it.isAbsolute) it else it.absoluteFile }
            .map { it.normalize() }
        if (changedFiles.isEmpty()) {
            throw IncrementalException("Param 'changedFiles' is empty.")
        }
        // 验证所有文件是否存在
        changedFiles.forEach { file ->
            if (!file.exists()) {
                throw IncrementalException("Changed file not exists: ${file.absolutePath}")
            }
        }

        val logLevelValue = keyValueMap["logLevel"] ?: "INFO"
        val logLevel = Level.toLevel(logLevelValue)

        return Params(
            baseBuildJuggRootDir = baseBuildJuggRootDir,
            sourceProjectDir = sourceProjectDir,
            outputApkDir = outputApkDir,
            changedFiles = changedFiles,
            logLevel = logLevel,
        )
    }
}
