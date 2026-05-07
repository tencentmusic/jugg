package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.TestFilter

/**
 * InstrumentMcpToolAction implements MCP tool `instrument`.
 *
 * This tool only translates `am instrument`-style arguments into [AndroidTestRunSpec],
 * then reuses the existing Jugg compile/deploy pipeline.
 */
class InstrumentMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.INSTRUMENT

    // Keep aliases close to adb/am wording so agents can map arguments with lower cognitive load.
    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Run androidTest with am instrument-like arguments via Jugg compile/deploy pipeline.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "class" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Class filter in am instrument format. Supports comma-separated values and class#method entries.",
                ),
                "clazz" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Alias for class.",
                ),
                "package" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Equivalent to am instrument -e package <package>.",
                ),
                "testPackage" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Alias for package.",
                ),
                "testsRegex" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Equivalent to am instrument -e tests_regex <regex>.",
                ),
                "regex" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Alias for testsRegex.",
                ),
                "runner" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Instrumentation runner override.",
                ),
                "instrumentationRunner" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Alias for runner.",
                ),
                "e" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Alias for extras.",
                    additionalProperties = true,
                ),
                "extras" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Additional am instrument -e key value pairs. Values must be strings.",
                    additionalProperties = true,
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val specResult = parseSpec(arguments)
        if (specResult.error != null) {
            return specResult.error
        }
        val spec = specResult.spec ?: AndroidTestRunSpec(
            testClass = null,
            testMethod = null,
        )
        return CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = toolName,
            isSkipDeploy = false,
            isAlwaysRestartApp = false,
            androidTestRunSpec = spec,
            buildTargetOverride = BuildTarget.ANDROID_TEST,
        )
    }

    private fun parseSpec(arguments: Map<String, Any?>): ParseSpecResult {
        val classArg = firstNonBlankString(arguments, "class", "clazz")
        val packageArg = firstNonBlankString(arguments, "package", "testPackage")
        val testsRegexArg = firstNonBlankString(arguments, "testsRegex", "regex")
        val runner = firstNonBlankString(arguments, "runner", "instrumentationRunner").ifEmpty { null }

        val filters = parseClassFilters(classArg)

        val extras = mutableListOf<Pair<String, String>>()
        if (packageArg.isNotEmpty()) {
            extras += "package" to packageArg
        }
        if (testsRegexArg.isNotEmpty()) {
            extras += "tests_regex" to testsRegexArg
        }

        @Suppress("UNCHECKED_CAST")
        val rawExtras = arguments["extras"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val rawExtrasAlias = arguments["e"] as? Map<String, Any?>
        val mergedExtras = linkedMapOf<String, Any?>()
        rawExtrasAlias?.let { mergedExtras.putAll(it) }
        rawExtras?.let { mergedExtras.putAll(it) }
        if (mergedExtras.isNotEmpty()) {
            for ((key, value) in mergedExtras) {
                if (key.isBlank()) {
                    return ParseSpecResult(
                        error = invalidParams("extras contains a blank key"),
                    )
                }
                val stringValue = value as? String
                    ?: return ParseSpecResult(error = invalidParams("extras.$key must be a string"))
                extras += key to stringValue
            }
        }

        return ParseSpecResult(
            spec = AndroidTestRunSpec(
                testClass = null,
                testMethod = null,
                testFilters = filters,
                extraArgs = extras,
                runnerOverride = runner,
            ),
        )
    }

    private fun parseClassFilters(raw: String): List<TestFilter> {
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split(",")
            .mapNotNull { item ->
                val token = item.trim()
                if (token.isEmpty()) return@mapNotNull null
                val className = token.substringBefore("#").trim()
                if (className.isEmpty()) return@mapNotNull null
                val methodName = token.substringAfter("#", "").trim().ifEmpty { null }
                TestFilter(className = className, methodName = methodName)
            }
    }

    private fun firstNonBlankString(arguments: Map<String, Any?>, vararg keys: String): String {
        for (key in keys) {
            val value = (arguments[key] as? String).orEmpty().trim()
            if (value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    private fun invalidParams(reason: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: $reason.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
    }

    private data class ParseSpecResult(
        val spec: AndroidTestRunSpec? = null,
        val error: McpToolResult? = null,
    )
}
