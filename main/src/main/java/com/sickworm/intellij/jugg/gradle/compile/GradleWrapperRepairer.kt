package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Repairs incomplete Gradle Wrapper launch files when a project already declares wrapper properties.
 */
class GradleWrapperRepairer(
    private val logger: Logger,
) {

    fun repairIfNeeded(
        projectDir: File,
        compileCommand: String,
        normalizeGradlewLineEndings: Boolean,
    ): GradleWrapperRepairResult {
        val wrapperExecutable = resolveWrapperExecutable(projectDir, compileCommand)
            ?: return GradleWrapperRepairResult.Skipped
        val wrapperDir = wrapperExecutable.parentFile ?: projectDir
        val wrapperProperties = File(wrapperDir, WRAPPER_PROPERTIES_PATH)
        if (!wrapperProperties.exists()) {
            return GradleWrapperRepairResult.Skipped
        }

        var repaired = false
        val missingFiles = missingWrapperFiles(wrapperDir)
        if (missingFiles.isNotEmpty()) {
            logger.info("[Jugg] Gradle wrapper files are incomplete, missing: ${missingFiles.joinToString()}")
            copyMissingResource(wrapperDir, "gradlew", RESOURCE_GRADLEW)
            copyMissingResource(wrapperDir, "gradlew.bat", RESOURCE_GRADLEW_BAT)
            copyMissingResource(wrapperDir, "gradle/wrapper/gradle-wrapper.jar", RESOURCE_GRADLE_WRAPPER_JAR)
            File(wrapperDir, "gradlew").setExecutable(true)
            logger.info("[Jugg] Filled missing Gradle wrapper files successfully.")
            repaired = true
        }

        if (normalizeGradlewLineEndings && wrapperExecutable.name == "gradlew") {
            repaired = normalizeCrlfLineEndings(File(wrapperDir, "gradlew")) || repaired
        }

        return if (repaired) GradleWrapperRepairResult.Repaired else GradleWrapperRepairResult.Skipped
    }

    private fun resolveWrapperExecutable(projectDir: File, compileCommand: String): File? {
        val executableToken = compileCommand.split(Regex("\\s+"))
            .map { it.trim().trim('"', '\'') }
            .firstOrNull { it.isGradlewExecutableToken() }
            ?: return null

        val normalizedToken = executableToken.replace('\\', '/')
        if (normalizedToken.startsWith("/") || normalizedToken.contains(":")) {
            return null
        }
        val executableFile = File(projectDir, normalizedToken).normalize()
        return executableFile.takeIf { it.isChild(projectDir) }
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

    private fun normalizeCrlfLineEndings(gradlew: File): Boolean {
        val original = gradlew.readBytes()
        val normalized = ByteArray(original.size)
        var sourceIndex = 0
        var targetIndex = 0
        while (sourceIndex < original.size) {
            if (sourceIndex + 1 < original.size &&
                original[sourceIndex] == '\r'.code.toByte() &&
                original[sourceIndex + 1] == '\n'.code.toByte()
            ) {
                normalized[targetIndex++] = '\n'.code.toByte()
                sourceIndex += 2
            } else {
                normalized[targetIndex++] = original[sourceIndex++]
            }
        }
        if (targetIndex == original.size) {
            return false
        }
        gradlew.writeBytes(normalized.copyOf(targetIndex))
        logger.info("[Jugg] Converted gradlew line endings from CRLF to LF for remote compilation: " +
                gradlew.absolutePath)
        return true
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
