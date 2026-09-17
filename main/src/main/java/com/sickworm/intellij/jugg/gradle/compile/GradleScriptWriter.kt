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
        val bundled = GradleScriptWriter::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!
            .openStream().use { it.reader().readText() }
        val current = if (initGradleFile.exists()) initGradleFile.readText() else null
        val runtimeJar = pathManager.runtimeJarFilePath
        if (hasWrote && current == bundled && runtimeJar.exists()) {
            return
        }
        TimeLogger.start("writeInitGradleFile")
        initGradleFile.parentFile.mkdirs()
        initGradleFile.writeText(bundled)
        GradleScriptWriter::class.java.getResource(BuildConfig.RUNTIME_JAR_PATH)!!.openStream().use { ins ->
            runtimeJar.writeBytes(ins.readAllBytes())
        }
        hasWrote = true
        TimeLogger.end("writeInitGradleFile", logger)
    }

}
