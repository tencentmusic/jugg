package com.sickworm.intellij.jugg.cmdline.base

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.cmdline.baselineConstRefDbFile
import com.sickworm.intellij.jugg.cmdline.createBaselineConstRefEngine
import com.sickworm.intellij.jugg.cmdline.CmdLineTaskRuntime
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Builds the const-ref index shipped with a CI baseline, reusing the index of a restored previous baseline.
 */
internal class CiConstRefBaseline(private val paths: JuggPathManager, private val logger: Logger) {
    private val scratch = Files.createTempDirectory(paths.stableGradleDir.toPath(), "const-ref-").toFile()
    private val scratchDbFile = File(scratch, paths.baselineConstRefDbFile.name)

    suspend fun build(model: JuggProjectInfo, previousBaselineDir: File?) {
        try {
            val started = System.nanoTime()
            previousBaselineDir?.let(::restore)
            logger.info("Scan Gradle source roots for baseline constant references.")
            scan(model)
            val baselineDbFile = paths.baselineConstRefDbFile
            baselineDbFile.parentFile.mkdirs()
            Files.move(scratchDbFile.toPath(), baselineDbFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            logger.info("Publish ConstRef index in ${(System.nanoTime() - started) / 1_000_000}ms.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Baseline constant reference scan failed, discard the partial index", e)
        }
    }

    fun close() {
        if (!scratch.deleteRecursively()) {
            logger.warn("Could not remove temporary ConstRef baseline directory: $scratch")
        }
    }

    private fun restore(baselineDir: File) {
        val previousDbFile = JuggPathManager(paths.projectDir, baselineDir).baselineConstRefDbFile
        if (!previousDbFile.isFile) {
            logger.info("Previous baseline has no ConstRef index, scan without reuse.")
            return
        }
        try {
            previousDbFile.copyTo(scratchDbFile)
        } catch (e: IOException) {
            logger.warn("Previous ConstRef index unavailable, scan without reuse: $previousDbFile", e)
            scratchDbFile.delete()
        }
    }

    private suspend fun scan(model: JuggProjectInfo) {
        val modules = model.modules.values
        val sourceDirs = modules.flatMap { it.sourceDirs }
        // Build outputs and declared external generated sources are archived with the classpath instead.
        val excludedDirs = modules.flatMap { module ->
            val generatedDirs = module.externalBuildInfos.flatMap { build ->
                build.prerequisites.flatMap { task -> task.generatedSourceDirs.map { it.directory } }
            }
            generatedDirs + module.buildPathInfo.buildDir
        }.map { it.absoluteFile.normalize() }.toSet()

        val runtime = CmdLineTaskRuntime(paths, CoroutineScope(currentCoroutineContext()), logger)
        val engine = paths.createBaselineConstRefEngine(
            runtime.taskRunnerManager, logger, scratchDbFile,
        )
        try {
            engine.scanBaseline(sourceDirs, excludedDirs)
        } finally {
            engine.dispose()
            runtime.dispose()
        }
    }
}
