package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.deploy.FullBuildInfoSerializer
import com.sickworm.intellij.jugg.ai.mcp.util.LastCompileTimestampRegistry
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_FILE_PATHS = 20
private const val REFRESH_CHANGES_PARAM = "refreshChanges"
private const val FULL_INFO_PARAM = "fullInfo"
private val READABLE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * GetStatusMcpToolAction implements MCP tool `status` and returns current deploy state,
 * uncompiled file counts by type, and absolute file paths in summary or full mode.
 */
class GetStatusMcpToolAction(
    private val lastCompileTimestampRegistry: LastCompileTimestampRegistry = LastCompileTimestampRegistry.INSTANCE,
) : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.GET_STATUS

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Return current Jugg deploy state, uncompiled file counts by type, " +
            "and absolute paths of modified files.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                REFRESH_CHANGES_PARAM to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, refresh git-tracked changed files before reading status. Default is true.",
                ),
                FULL_INFO_PARAM to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, return full status information. Currently this removes the " +
                        "$MAX_FILE_PATHS-path limit from data.files. Default is false.",
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "hasDevice" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when a device is connected and ready (state is not NOTHING_CAN_DO).",
                        ),
                        "needFallback" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when a full Gradle build is required (state is READY_FULL_COMPILE).",
                        ),
                        "executionType" to McpJsonSchemaProperty(
                            type = "string",
                            `enum` = listOf("local", "remote"),
                            description = "Current Gradle fallback execution type from the active Jugg run configuration.",
                        ),
                        "stateMessage" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Human-readable reason for current state.",
                        ),
                        "pendingModifiedFiles" to McpJsonSchemaProperty(
                            type = "object",
                            description = "total and per-type counts of pending modified files.",
                            additionalProperties = true,
                        ),
                        "files" to McpJsonSchemaProperty(
                            type = "array",
                            description = "Absolute paths of uncompiled files. Returns at most $MAX_FILE_PATHS " +
                                "unless fullInfo=true.",
                            items = McpJsonSchemaProperty(type = "string"),
                        ),
                        "detail" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Empty when all files are listed. Natural-language note when the list is truncated, " +
                                "including guidance to request fullInfo=true.",
                        ),
                        "lastFileModifiedTime" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Readable local timestamp (yyyy-MM-dd HH:mm:ss) of latest uncompiled file modification. Empty when none.",
                        ),
                        "lastCompileTime" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Readable local timestamp (yyyy-MM-dd HH:mm:ss) of latest compile/deploy/gradle-build invocation. Empty when none.",
                        ),
                        "enabledAndroidTest" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when the last persisted full-build baseline was built with AndroidTest target.",
                        ),
                        "hasBeenFullCompiled" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when Jugg has persisted a complete full-build baseline for this project.",
                        ),
                        "isCompiling" to McpJsonSchemaProperty(
                            type = "boolean",
                            description = "True when a Jugg compile/deploy run task is currently executing.",
                        ),
                    ),
                    required = listOf(
                        "hasDevice",
                        "needFallback",
                        "executionType",
                        "stateMessage",
                        "pendingModifiedFiles",
                        "files",
                        "detail",
                        "lastFileModifiedTime",
                        "lastCompileTime",
                        "enabledAndroidTest",
                        "hasBeenFullCompiled",
                        "isCompiling",
                    ),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val fullInfo = arguments[FULL_INFO_PARAM] as? Boolean ?: false
        if (shouldRefreshChanges(arguments)) {
            runtime.refreshChangedFilesForStatus()
        }

        val deployState = runtime.deployStateManager?.updateDeployState()
            ?: return McpToolResult.internalErrorResult(toolName, "deploy state manager is unavailable")

        val deployFileManager = runtime.deployFileManager
            ?: return McpToolResult.internalErrorResult(toolName, "deploy file manager is unavailable")

        val fallbackReason: String? = runtime.incrementalCompileFallbackChecker?.checkFallback()
        val needFallback = fallbackReason != null || deployState.state == JuggDeployState.State.READY_FULL_COMPILE

        val uncompiledFiles: List<ChangedFile> = deployFileManager.getUncompiledFiles()

        val countsByType = uncompiledFiles
            .groupingBy { it.type.name }
            .eachCount()

        val pendingModifiedFiles: Map<String, Any> = mutableMapOf<String, Any>("total" to uncompiledFiles.size)
            .also { it.putAll(countsByType) }

        val total = uncompiledFiles.size
        val files: List<String> = (if (fullInfo) uncompiledFiles else uncompiledFiles.take(MAX_FILE_PATHS))
            .map { it.file.absolutePath }
        val truncationNote: String = if (!fullInfo && total > MAX_FILE_PATHS) {
            "Showing $MAX_FILE_PATHS of $total files. Set fullInfo=true to return full status information, " +
                "including all $total file paths."
        } else {
            ""
        }
        val detail: String = when {
            fallbackReason != null && truncationNote.isNotEmpty() -> "$fallbackReason\n$truncationNote"
            fallbackReason != null -> fallbackReason
            else -> truncationNote
        }
        val maxLastModifiedMillis: Long = uncompiledFiles
            .asSequence()
            .map { changedFile -> changedFile.file.takeIf { it.exists() }?.lastModified() ?: 0L }
            .maxOrNull()
            ?: 0L
        val lastFileModifiedTime: String = if (maxLastModifiedMillis > 0L) {
            Instant.ofEpochMilli(maxLastModifiedMillis)
                .atZone(ZoneId.systemDefault())
                .format(READABLE_TIME_FORMATTER)
        } else {
            ""
        }
        val projectDir = (arguments["projectDir"] as? String)
            ?: runCatching { runtime.project.basePath }.getOrNull()
        val lastCompileTime = projectDir?.let { lastCompileTimestampRegistry.getTimestamp(it) } ?: ""
        val enabledAndroidTest = projectDir?.let { isAndroidTestEnabledAtLastFullBuild(it) } ?: false
        val hasBeenFullCompiled = projectDir?.let { hasBeenFullCompiled(File(it)) } ?: false

        val data: Map<String, Any> = mapOf(
            "hasDevice" to (runtime.deployTargetManager.hasDevice),
            "needFallback" to needFallback,
            "executionType" to runtime.forceGradleCompileHelper.resolveExecutionType(),
            "stateMessage" to deployState.msg,
            "pendingModifiedFiles" to pendingModifiedFiles,
            "files" to files,
            "detail" to detail,
            "lastFileModifiedTime" to lastFileModifiedTime,
            "lastCompileTime" to lastCompileTime,
            "enabledAndroidTest" to enabledAndroidTest,
            "hasBeenFullCompiled" to hasBeenFullCompiled,
            "isCompiling" to runtime.juggConfigurationRunner.isCompiling,
        )

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "status executed successfully.",
            data = data,
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    private fun isAndroidTestEnabledAtLastFullBuild(projectDir: String): Boolean {
        val fullBuildInfoFile = File(JuggPathManager(File(projectDir)).compileContextDbDir, "full_build_info.json")
        if (!fullBuildInfoFile.exists()) {
            return false
        }
        return runCatching {
            FullBuildInfoSerializer().deserialize(fullBuildInfoFile.readText(Charsets.UTF_8)).buildTarget == BuildTarget.ANDROID_TEST
        }.getOrDefault(false)
    }

    private fun shouldRefreshChanges(arguments: Map<String, Any?>): Boolean {
        return arguments[REFRESH_CHANGES_PARAM] as? Boolean ?: true
    }

}
