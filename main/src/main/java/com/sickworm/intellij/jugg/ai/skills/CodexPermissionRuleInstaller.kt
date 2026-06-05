package com.sickworm.intellij.jugg.ai.skills

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.skills.agents.AgentPermissionRuleTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs Codex command permission rules required by bundled Jugg skills.
 */
object CodexPermissionRuleInstaller {

    fun install(targets: List<AgentPermissionRuleTarget>, logger: Logger? = null): List<CodexPermissionRuleInstallResult> {
        return targets.map { target ->
            try {
                installTarget(target).also { result ->
                    logger?.info(
                        "[Install Codex Permission Rules] rulesFile=${result.rulesFile.path}, " +
                            "status=${result.status}, prefix=[${target.prefixPattern.joinToString(", ")}]",
                    )
                }
            } catch (error: Throwable) {
                logger?.warn(
                    "[Install Codex Permission Rules] rulesFile=${target.rulesFile.path}, " +
                        "status=fail, prefix=[${target.prefixPattern.joinToString(", ")}]",
                    error,
                )
                throw error
            }
        }
    }

    private fun installTarget(target: AgentPermissionRuleTarget): CodexPermissionRuleInstallResult {
        val ruleLine = buildPrefixRuleLine(target.prefixPattern)
        val existingContent = if (target.rulesFile.isFile) {
            target.rulesFile.readText(StandardCharsets.UTF_8)
        } else {
            ""
        }
        val existingLines = existingContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (ruleLine in existingLines) {
            return CodexPermissionRuleInstallResult(target.rulesFile, "already_installed")
        }
        val nextContent = appendLine(existingContent, ruleLine)
        writeAtomically(target.rulesFile, nextContent)
        return CodexPermissionRuleInstallResult(target.rulesFile, "installed")
    }

    private fun buildPrefixRuleLine(prefixPattern: List<String>): String {
        val pattern = prefixPattern.joinToString(", ") { value -> "\"${escapeRuleString(value)}\"" }
        return "prefix_rule(pattern=[$pattern], decision=\"allow\")"
    }

    private fun escapeRuleString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun appendLine(existingContent: String, line: String): String {
        if (existingContent.isEmpty()) {
            return "$line\n"
        }
        val separator = if (existingContent.endsWith("\n")) "" else "\n"
        return "$existingContent$separator$line\n"
    }

    private fun writeAtomically(targetFile: File, content: String) {
        val parent = targetFile.parentFile ?: throw IOException("missing_parent_dir")
        parent.mkdirs()

        val tempFile = File(parent, "${targetFile.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            moveFile(tempFile, targetFile)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun moveFile(tempFile: File, targetFile: File) {
        val sourcePath = tempFile.toPath()
        val targetPath = targetFile.toPath()
        try {
            Files.move(
                sourcePath,
                targetPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Result for one Codex permission rules file write.
 */
data class CodexPermissionRuleInstallResult(
    val rulesFile: File,
    val status: String,
)
