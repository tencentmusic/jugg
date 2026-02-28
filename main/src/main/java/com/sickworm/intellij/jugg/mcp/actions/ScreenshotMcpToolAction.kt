package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

/**
 * ScreenshotMcpToolAction implements MCP tool `screenshot` and converts request arguments into tool execution and MCP result payloads.
 */
class ScreenshotMcpToolAction : McpToolAction {
    override val toolName: String = "screenshot"
    private val maxLongEdgePx = 1440
    private val jpegQuality = 0.62f
    private val forceOptimizeSizeBytes = 500_000L

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Capture screenshot from target device.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.(png|jpg|jpeg)$"),
                    ),
                    required = listOf("file"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return screenshotAction(runtime)
    }

    private fun screenshotAction(runtime: IMcpRuntime): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("screenshot")
        val adb = selected.adb
        val toolDir = ensureToolDir(runtime, "screenshot")
            ?: return McpToolResult.internalErrorResult("screenshot", "failed to prepare artifact directory")

        val fileName = "screenshot_${System.currentTimeMillis()}.png"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        return try {
            adb.execAdbShellCmd("mkdir -p $remoteDir")
            adb.execAdbShellCmd("screencap -p $remoteFile")
            if (!adb.pull(remoteFile, localFile) || !localFile.exists()) {
                return McpToolResult.internalErrorResult("screenshot", "failed to pull screenshot file")
            }
            val outputFile = optimizeForUpload(localFile)

            McpToolResult(
                status = McpToolStatus.OK,
                message = "screenshot executed successfully.",
                data = mapOf(
                    "file" to outputFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "image", path = outputFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("screenshot", e.message ?: "unknown error")
        }
    }

    /**
     * Optimize screenshot for upload/token cost:
     * 1) if long edge is too large or file is too heavy, resize;
     * 2) encode to jpeg for better compression.
     * Falls back to original file when optimize step fails.
     */
    private fun optimizeForUpload(inputFile: File): File {
        val sourceImage = ImageIO.read(inputFile) ?: return inputFile
        val sourceWidth = sourceImage.width
        val sourceHeight = sourceImage.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return inputFile
        }

        val longEdge = maxOf(sourceWidth, sourceHeight)
        val shouldScale = longEdge > maxLongEdgePx
        val shouldCompress = inputFile.length() > forceOptimizeSizeBytes
        if (!shouldScale && !shouldCompress) {
            return inputFile
        }

        val scale = if (shouldScale) {
            maxLongEdgePx.toDouble() / longEdge.toDouble()
        } else {
            1.0
        }
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val targetImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = targetImage.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null)
        graphics.dispose()

        val outputFile = File(inputFile.parentFile, inputFile.nameWithoutExtension + "_opt.jpg")
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) {
            return inputFile
        }
        val writer = writers.next()
        return try {
            FileImageOutputStream(outputFile).use { output ->
                writer.output = output
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = jpegQuality
                }
                writer.write(null, IIOImage(targetImage, null, null), param)
            }
            if (outputFile.exists() && outputFile.length() > 0) {
                outputFile
            } else {
                inputFile
            }
        } catch (_: Throwable) {
            inputFile
        } finally {
            writer.dispose()
        }
    }

    /**
     * SelectedAdb carries adb and messageDetail.
     */
    private data class SelectedAdb(
        val adb: IDeviceAdb,
    )

    private fun resolveOnlineDevice(runtime: IMcpRuntime): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb)
    }

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, toolName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun noDeviceResult(toolName: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }

}
