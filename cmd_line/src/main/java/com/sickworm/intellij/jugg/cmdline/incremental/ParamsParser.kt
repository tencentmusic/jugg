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

        val baseBuildProjectDirValue = keyValueMap["baseBuildProjectDir"] ?: ""
        if (baseBuildProjectDirValue.isEmpty()) {
            throw IncrementalException("Param 'baseBuildProjectDir' not found.")
        }
        val baseBuildProjectDir = File(baseBuildProjectDirValue).normalize()
        if (!baseBuildProjectDir.exists()) {
            throw IncrementalException("Param 'baseBuildProjectDir' invalid, not exists: $baseBuildProjectDirValue")
        }

        val sourceProjectDirValue = keyValueMap["sourceProjectDir"] ?: ""
        if (sourceProjectDirValue.isEmpty()) {
            throw IncrementalException("Param 'sourceProjectDir' not found.")
        }
        val sourceProjectDir = File(sourceProjectDirValue).normalize()
        if (!sourceProjectDir.exists()) {
            throw IncrementalException("Param 'sourceProjectDir' invalid, not exists: $sourceProjectDirValue")
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
            .map { File(it).normalize() }
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
            baseBuildProjectDir = baseBuildProjectDir,
            sourceProjectDir = sourceProjectDir,
            outputApkDir = outputApkDir,
            changedFiles = changedFiles,
            logLevel = logLevel,
        )
    }
}
