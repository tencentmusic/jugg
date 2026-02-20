package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.JuggPathManager

/**
 * GradleScriptWriter writes gradle script output.
 */
class GradleScriptWriter(
    private val pathManager: JuggPathManager,
    private val logger: Logger,
) {

    private var hasWrote = false

    @Synchronized
    fun writeInitGradleFile() {
        val initGradleFile = pathManager.initGradleFilePath
        if (hasWrote && initGradleFile.exists()) {
            return
        }
        TimeLogger.start("writeInitGradleFile")
        initGradleFile.parentFile.mkdirs()
        GradleScriptWriter::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
            val text = ins.reader().readText()
            initGradleFile.writeText(text)
        }

        GradleScriptWriter::class.java.getResource(BuildConfig.RUNTIME_JAR_PATH)!!.openStream().use { ins ->
            pathManager.runtimeJarFilePath.writeBytes(ins.readAllBytes())
        }

        hasWrote = true
        TimeLogger.end("writeInitGradleFile", logger)
    }

}
