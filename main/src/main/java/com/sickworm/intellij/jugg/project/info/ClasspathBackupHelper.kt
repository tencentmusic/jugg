package com.sickworm.intellij.jugg.project.info

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File

/**
 * Obtain all the compilation artifacts we need for subsequent incremental compilation.
 */
class ClasspathBackupHelper(
    private val compileClient: IGradleCompileClient,
    private val progressIndicator: ProgressIndicator?,
    private val coroutineScope: CoroutineScope,
    private val logger: Logger,
    private val indicatorUpdateInterval: Long = 16,
) {

    fun fetch(projectInfo: JuggProjectInfo): JuggProjectInfo? {
        var allModules = projectInfo.modules
        val moduleBuildPathInfos = allModules.map { it.value.buildPathInfo }

        val (costTime, classpathRootDir) = measureTimeMillisWithResult {
            val originText = progressIndicator?.text
            progressIndicator?.text = "Jugg: Fetching classpath..."

            var updateJob: Job? = null
            var syncCount = 0
            val terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
                override fun onOutput(line: String, isNeedPrint: Boolean) {
                    syncCount++
                    if (updateJob?.isActive == true) {
                        return
                    }
                    updateJob = coroutineScope.launch {
                        try {
                            delay(indicatorUpdateInterval)
                            progressIndicator?.text = "Jugg: Fetching classpath... (synced $syncCount)"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // ignore
                        } catch (e: Exception) {
                            logger.debug("fetchClasspathResult updateJob failed $e")
                        }
                    }
                }

                override fun onOutputErr(line: String) {
                }
            }
            val result = fetchClasspathResult(moduleBuildPathInfos, terminalOutputListener)
            progressIndicator?.text = originText
            updateJob?.cancel()
            return@measureTimeMillisWithResult result
        }
        logger.debug("fetchClasspathResult cost ${costTime}ms")
        logger.debug("fetchClasspathResult classpathRootDir = $classpathRootDir," +
                "exists = ${classpathRootDir?.exists()}, children = ${classpathRootDir?.listFiles()?.map { it.path }}")
        if (classpathRootDir != null && classpathRootDir.exists()) {
            // wrap local CompileContextInfo to CompileContextInfo fetched from build
            allModules = allModules.values
                .map {
                    it.copy(buildPathInfo = ModuleBuildPathInfo(
                        classpathRootDir,
                        File(classpathRootDir, it.buildPathInfo.modulePathRelative.path),
                        it.buildVariant,
                        customSyncFilePath = it.buildPathInfo.customSyncFilePath,
                        buildDirRelativePath = it.buildPathInfo.buildDirRelativePath,
                    )
                    )
                }
                .associateBy { it.name }
            val logInfo = allModules.entries.joinToString {
                "${it.key}: ${it.value.buildPathInfo.buildDir}: exists: ${it.value.moduleRootDir.exists()}"
            }
            logger.debug("Fetch classpath success, dir info: $logInfo")
            return projectInfo.copy(modules = allModules)
        } else {
            logger.warn("Fetch classpath failed, please check log for details.")
            return null
        }
    }

    private fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>, terminalOutputListener: IGradleCompileClient.TerminalOutputListener): File? {
        compileClient.terminalOutputListener = terminalOutputListener
        val result = compileClient.fetchClasspathResult(buildDirs)
        compileClient.terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT
        return result
    }

}
