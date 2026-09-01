package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpArtifact
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.diagnostics.IssueReportBundleBuilder
import com.sickworm.intellij.jugg.diagnostics.IssueReportBundleReader
import com.sickworm.intellij.jugg.diagnostics.IssueReportEntry
import com.sickworm.intellij.jugg.diagnostics.IssueReportUploader
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
import java.io.File

/** Prepares the exact redacted diagnostics archive that the CLI presents for confirmation. */
class PrepareIssueReportMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.REPORT_PREPARE
    override val definition = McpToolDefinition(
        name = toolName,
        description = "Prepare a redacted Jugg diagnostics archive for user review before upload.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf("projectDir" to McpToolSchemas.projectDirProperty),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = reportOutputSchema(),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return runCatching {
            val projectDir = File(runtime.projectDir)
            val pathManager = JuggPathManager(projectDir)
            val settings = JuggSettings.defaultCompileSettings
            val knownSecrets = setOfNotNull(
                settings.remoteSshPassword,
                settings.remoteSshUser,
                settings.remoteSshIp,
                GitManager.createGitManagerAndTrySearchParent(projectDir).userName,
                System.getProperty("user.name"),
            )
            val runtimeInfo = PlatformApi.getRuntimeInfo()
            val builder = IssueReportBundleBuilder(
                pathManager.diagnosticsDir,
                projectDir,
                File(System.getProperty("user.home")),
                runtime.logger,
            )
            val candidates = builder.prepare(
                environment = mapOf(
                    "pluginVersion" to PluginInfoReader.getPluginVersion(),
                    "runtimeType" to runtimeInfo.runtimeType,
                    "runtimeVersion" to runtimeInfo.runtimeVersion,
                    "hostVersion" to runtimeInfo.hostVersion,
                    "os" to System.getProperty("os.name"),
                    "jvm" to System.getProperty("java.version"),
                ),
                projectSummary = emptyMap(),
                logFiles = IssueReportBundleBuilder.selectRecentLogFiles(
                    pathManager.logDir,
                    pathManager.standaloneCliLogDir,
                ),
                standaloneLogDir = pathManager.standaloneCliLogDir,
                logcat = runtime.deployTargetManager.dumpErrorLogs(),
                hookDebugLog = File(JuggGlobalPathManager.rootDir, "skills/hooks/jugg-hook-debug.log"),
                knownSecrets = knownSecrets,
            )
            val bundle = builder.build(candidates.map { it.path }.toSet())
            val prepared = IssueReportBundleReader(pathManager.diagnosticsDir).load(bundle.reportId)
            McpToolResult(
                status = McpToolStatus.OK,
                message = "report-prepare executed successfully. Review the prepared bundle before upload.",
                data = mapOf(
                    "reportId" to bundle.reportId,
                    "filePath" to bundle.file.absolutePath,
                    "size" to prepared.content.size,
                    "sha256" to prepared.sha256,
                    "uploadUrl" to IssueReportUploader.JUGG_REPORT_URL,
                    "entries" to prepared.archiveEntries.map(::entryData),
                ),
                artifacts = listOf(McpArtifact("file", bundle.file.absolutePath)),
            )
        }.getOrElse { error ->
            McpToolResult.internalErrorResult(toolName, error.message ?: "Unable to prepare diagnostics")
        }
    }
}

/** Uploads only the prepared diagnostics archive whose digest the user reviewed. */
class UploadIssueReportMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.REPORT_UPLOAD
    override val definition = McpToolDefinition(
        name = toolName,
        description = "Verify and upload a diagnostics archive after explicit user confirmation.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "reportId" to McpJsonSchemaProperty(type = "string", pattern = "^[0-9a-f]{8}$"),
                "sha256" to McpJsonSchemaProperty(type = "string", pattern = "^[0-9a-fA-F]{64}$"),
            ),
            required = listOf("projectDir", "reportId", "sha256"),
            additionalProperties = false,
        ),
        outputSchema = reportOutputSchema(),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val reportId = arguments["reportId"] as? String
            ?: return invalidParams("reportId is required")
        val expectedSha256 = arguments["sha256"] as? String
            ?: return invalidParams("sha256 is required")
        val prepared = runCatching {
            val outputDir = JuggPathManager(File(runtime.projectDir)).diagnosticsDir
            IssueReportBundleReader(outputDir).load(reportId, expectedSha256)
        }.getOrElse { error ->
            return McpToolResult.internalErrorResult(toolName, error.message ?: "Invalid diagnostics bundle")
        }
        val uploadResult = IssueReportUploader().upload(
            prepared.bundle,
            IssueReportUploader.JUGG_REPORT_URL,
            prepared.content,
        )
        if (!uploadResult.isSuccess) {
            return McpToolResult.internalErrorResult(
                toolName,
                uploadResult.errorMessage ?: "Upload failed",
            )
        }
        return reportUploadSuccessResult(uploadResult.reportId ?: reportId)
    }

    private fun invalidParams(reason: String) = McpToolResult(
        status = McpToolStatus.ERROR,
        message = "$toolName failed. Reason: $reason.",
        errorCode = McpErrorCode.INVALID_PARAMS,
    )
}

internal fun reportUploadSuccessResult(reportId: String) = McpToolResult(
    status = McpToolStatus.OK,
    message = "Report uploaded. Jugg Report ID: $reportId",
    data = mapOf("reportId" to reportId),
)

private fun entryData(entry: IssueReportEntry): Map<String, Any> = mapOf(
    "path" to entry.path,
    "size" to entry.size,
    "sensitivity" to entry.sensitivity.name,
    "redaction" to entry.redaction,
)

private fun reportOutputSchema() = McpToolSchemas.baseOutputSchema
