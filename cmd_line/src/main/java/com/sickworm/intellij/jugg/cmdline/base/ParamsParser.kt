package com.sickworm.intellij.jugg.cmdline.base

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
            throw BaseBuildException("Param 'baseBuildProjectDir' not found.")
        }
        var baseBuildProjectDir = File(baseBuildProjectDirValue)
        if (!baseBuildProjectDir.exists()) {
            throw BaseBuildException("Param 'baseBuildProjectDir' invalid, not exists: $baseBuildProjectDirValue")
        }
        if (!baseBuildProjectDir.isAbsolute) {
            baseBuildProjectDir = baseBuildProjectDir.absoluteFile
        }

        val gradleCompileTask = keyValueMap["gradleCompileTask"] ?: ""
        if (gradleCompileTask.isEmpty()) {
            throw BaseBuildException("Param 'gradleCompileTask' not found.")
        }

        val gradleOutputApkPath = keyValueMap["gradleOutputApkPath"] ?: ""
        if (gradleOutputApkPath.isEmpty()) {
            throw BaseBuildException("Param 'gradleOutputApkPath' not found.")
        }

        val outputApkDirValue = keyValueMap["outputApkDir"] ?: ""
        val outputApkDir = if (outputApkDirValue.isEmpty()) {
            null
        } else {
            File(outputApkDirValue)
        }

        val logLevelValue = keyValueMap["logLevel"] ?: "DEBUG"
        val logLevel = Level.toLevel(logLevelValue)

        return Params(
            baseBuildProjectDir = baseBuildProjectDir,
            gradleCompileTask = gradleCompileTask,
            gradleOutputApkPath = gradleOutputApkPath,
            outputApkDir = outputApkDir,
            logLevel = logLevel,
        )
    }
}