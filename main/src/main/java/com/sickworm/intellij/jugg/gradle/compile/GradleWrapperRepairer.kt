package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Repairs incomplete Gradle Wrapper launch files when a project already declares wrapper properties.
 */
class GradleWrapperRepairer(
    private val logger: Logger,
) {

    fun repairIfNeeded(projectDir: File, compileCommand: String): GradleWrapperRepairResult {
        val wrapperDir = resolveWrapperProjectDir(projectDir, compileCommand) ?: return GradleWrapperRepairResult.Skipped
        val wrapperProperties = File(wrapperDir, WRAPPER_PROPERTIES_PATH)
        if (!wrapperProperties.exists()) {
            return GradleWrapperRepairResult.Skipped
        }

        val missingFiles = missingWrapperFiles(wrapperDir)
        if (missingFiles.isEmpty()) {
            return GradleWrapperRepairResult.Skipped
        }

        logger.info("[Jugg] Gradle wrapper files are incomplete, missing: ${missingFiles.joinToString()}")
        copyMissingResource(wrapperDir, "gradlew", RESOURCE_GRADLEW)
        copyMissingResource(wrapperDir, "gradlew.bat", RESOURCE_GRADLEW_BAT)
        copyMissingResource(wrapperDir, "gradle/wrapper/gradle-wrapper.jar", RESOURCE_GRADLE_WRAPPER_JAR)
        File(wrapperDir, "gradlew").setExecutable(true)
        logger.info("[Jugg] Filled missing Gradle wrapper files successfully.")
        return GradleWrapperRepairResult.Repaired
    }

    private fun resolveWrapperProjectDir(projectDir: File, compileCommand: String): File? {
        val executableToken = compileCommand.split(Regex("\\s+"))
            .map { it.trim().trim('"', '\'') }
            .firstOrNull { it.isGradlewExecutableToken() }
            ?: return null

        val normalizedToken = executableToken.replace('\\', '/')
        if (normalizedToken.startsWith("/") || normalizedToken.contains(":")) {
            return null
        }
        val executableFile = File(projectDir, normalizedToken).normalize()
        return executableFile.parentFile ?: projectDir
    }

    private fun String.isGradlewExecutableToken(): Boolean {
        val normalized = replace('\\', '/')
        return normalized == "gradlew" ||
            normalized == "gradlew.bat" ||
            normalized.endsWith("/gradlew") ||
            normalized.endsWith("/gradlew.bat")
    }

    private fun missingWrapperFiles(wrapperDir: File): List<String> {
        return listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar")
            .filter { !File(wrapperDir, it).exists() }
    }

    private fun copyMissingResource(wrapperDir: File, targetRelativePath: String, resourcePath: String) {
        val targetFile = File(wrapperDir, targetRelativePath)
        if (targetFile.exists()) {
            return
        }
        targetFile.parentFile?.mkdirs()
        val resource = GradleWrapperRepairer::class.java.getResourceAsStream(resourcePath)
            ?: error("Gradle wrapper repair resource missing: $resourcePath")
        resource.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        logger.info("[Jugg] Filled missing Gradle wrapper file: ${targetFile.absolutePath}")
    }

    companion object {
        private const val WRAPPER_PROPERTIES_PATH = "gradle/wrapper/gradle-wrapper.properties"
        private const val RESOURCE_GRADLEW = "/jugg/gradle/wrapper/gradlew"
        private const val RESOURCE_GRADLEW_BAT = "/jugg/gradle/wrapper/gradlew.bat"
        private const val RESOURCE_GRADLE_WRAPPER_JAR = "/jugg/gradle/wrapper/gradle-wrapper.jar"
    }
}

enum class GradleWrapperRepairResult {
    Skipped,
    Repaired,
}
