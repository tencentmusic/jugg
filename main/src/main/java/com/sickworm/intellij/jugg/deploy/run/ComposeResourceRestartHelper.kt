package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.logger.getInstance

/**
 * Handles the extra restart required by old Android Studio startup agents for modern Compose resources.
 */
internal class ComposeResourceRestartHelper(loggerArg: Logger) {

    private val logger = loggerArg.getInstance("ComposeResourceRestartHelper")

    fun isRequired(
        data: JuggDeployData,
        isDirectDeployCandidate: Boolean,
        isFirstDeploy: Boolean,
        hasRelaunchActivityIssues: Boolean,
    ): Boolean {
        if (isDirectDeployCandidate ||
            !isFirstDeploy ||
            data.isCompatDeploy ||
            !data.isFullRes ||
            !data.isComposeResourceCompiled ||
            data.overlays.none { it.name.startsWith("assets/composeResources/") }
        ) {
            return false
        }
        return hasRelaunchActivityIssues
    }

    fun waitUntilTransformCacheReady(sandbox: AppSandboxExecutor) {
        logger.info("Wait for Android Studio transform cache before the Compose resource compatibility restart.")
        repeat(POLL_COUNT) {
            if (isTransformCacheReady(sandbox)) {
                logger.debug("Android Studio transform cache is ready.")
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        logger.warn("Timed out waiting for Android Studio transform cache; restart app anyway.")
    }

    private fun isTransformCacheReady(sandbox: AppSandboxExecutor): Boolean {
        return try {
            sandbox.exec(CHECK_COMMAND).lineSequence().any { it.trim() == READY_OUTPUT }
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            logger.debug("Check Android Studio transform cache failed.", e)
            false
        }
    }

    private companion object {
        const val READY_OUTPUT = "__JUGG_AS_TRANSFORM_CACHE__ READY"
        const val POLL_COUNT = 50
        const val POLL_INTERVAL_MS = 100L
        val CHECK_COMMAND = """
            for cache in code_cache/.studio/instruments-*.jar.cache; do
              if [ -s "${'$'}cache/android-app-ResourcesManager" ] &&
                 [ -s "${'$'}cache/android-app-LoadedApk" ]; then
                echo $READY_OUTPUT
                break
              fi
            done
        """.trimIndent()
    }
}
