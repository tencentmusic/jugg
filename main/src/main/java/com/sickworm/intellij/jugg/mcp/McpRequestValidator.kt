package com.sickworm.intellij.jugg.mcp

class McpRequestValidator(
    private val currentProjectDir: String,
    private val toolRegistry: McpToolRegistry,
) {

    fun validate(request: McpJsonRpcRequest): McpValidationResult {
        return when (request.method) {
            McpJsonRpc.Method.ToolsList -> McpValidationResult.ToolsList
            McpJsonRpc.Method.ToolsCall -> validateToolsCall(request)
            else -> McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_METHOD_NOT_SUPPORTED,
                message = "Method not supported: ${request.method}",
                isJsonRpcError = true,
                jsonRpcCode = McpJsonRpc.ErrorCode.MethodNotFound,
            )
        }
    }

    private fun validateToolsCall(request: McpJsonRpcRequest): McpValidationResult {
        val params = request.params as? Map<*, *>
            ?: return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                message = "tools/call params is required",
            )

        val toolName = params["name"] as? String
            ?: return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                message = "Tool name is required",
            )

        if (!toolRegistry.hasTool(toolName)) {
            return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_TOOL_NOT_FOUND,
                message = "Tool not found: $toolName",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val args = params["arguments"] as? Map<String, Any?> ?: emptyMap()
        val toolDefinition = toolRegistry.getToolDefinition(toolName)
            ?: return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_TOOL_NOT_FOUND,
                message = "Tool not found: $toolName",
            )

        val normalizedArgs = applyDefaults(args, toolDefinition.inputSchema)
        val schemaValidation = validateAgainstSchema(toolName, normalizedArgs, toolDefinition.inputSchema)
        if (schemaValidation != null) {
            return schemaValidation
        }
        val projectDir = normalizedArgs["projectDir"] as? String

        if (toolName == "list_projects") {
            return McpValidationResult.ToolsCall(
                toolName = toolName,
                arguments = args,
                projectDir = projectDir.orEmpty(),
            )
        }

        if (projectDir.isNullOrBlank()) {
            return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                message = "$toolName failed. Reason: projectDir is required.",
            )
        }

        if (projectDir != currentProjectDir) {
            return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_PROJECT_NOT_INITIALIZED,
                message = "$toolName failed. Reason: project is not initialized.",
            )
        }

        return McpValidationResult.ToolsCall(
            toolName = toolName,
            arguments = normalizedArgs,
            projectDir = projectDir,
        )
    }

    private fun applyDefaults(arguments: Map<String, Any?>, schema: McpJsonSchemaObject): Map<String, Any?> {
        val normalized = arguments.toMutableMap()
        for ((name, property) in schema.properties) {
            if (!normalized.containsKey(name) && property.default != null) {
                normalized[name] = property.default
            }
        }
        return normalized
    }

    private fun validateAgainstSchema(
        toolName: String,
        arguments: Map<String, Any?>,
        schema: McpJsonSchemaObject,
    ): McpValidationResult.Invalid? {
        val unknownArgs = arguments.keys.filter { !schema.properties.containsKey(it) }
        if (schema.additionalProperties == false && unknownArgs.isNotEmpty()) {
            return invalidParams(toolName, "Unknown argument(s): ${unknownArgs.joinToString(", ")}")
        }

        for (required in schema.required) {
            if (!arguments.containsKey(required) || arguments[required] == null) {
                return invalidParams(toolName, "$required is required")
            }
        }

        for ((name, value) in arguments) {
            val property = schema.properties[name] ?: continue
            val validation = validateProperty(value = value, property = property, fieldPath = name)
            if (validation != null) {
                return invalidParams(toolName, validation)
            }
        }
        return null
    }

    private fun validateProperty(value: Any?, property: McpJsonSchemaProperty, fieldPath: String): String? {
        if (value == null) {
            return null
        }

        if (!matchesType(value, property.type)) {
            return "$fieldPath must be ${property.type}"
        }

        if (property.`enum` != null && property.`enum`.none { enumValue -> enumValue == value }) {
            return "$fieldPath must be one of ${property.`enum`.joinToString(", ")}"
        }

        when (property.type) {
            "string" -> {
                val stringValue = value as String
                if (property.pattern != null && !Regex(property.pattern).matches(stringValue)) {
                    return "$fieldPath does not match required pattern"
                }
            }

            "number" -> {
                val numericValue = (value as Number).toDouble()
                if (property.minimum != null && numericValue < property.minimum) {
                    return "$fieldPath must be >= ${formatNumber(property.minimum)}"
                }
                if (property.maximum != null && numericValue > property.maximum) {
                    return "$fieldPath must be <= ${formatNumber(property.maximum)}"
                }
            }

            "object" -> {
                @Suppress("UNCHECKED_CAST")
                val mapValue = value as Map<String, Any?>
                val nestedSchema = McpJsonSchemaObject(
                    properties = property.properties ?: emptyMap(),
                    required = property.required ?: emptyList(),
                    additionalProperties = property.additionalProperties,
                )
                val nestedValidation = validateAgainstSchema(toolName = fieldPath, arguments = mapValue, schema = nestedSchema)
                if (nestedValidation != null) {
                    return nestedValidation.message.substringAfter("Reason: ")
                }
            }

            "array" -> {
                val items = value as List<*>
                val itemSchema = property.items ?: return null
                items.forEachIndexed { index, item ->
                    val itemValidation = validateProperty(item, itemSchema, "$fieldPath[$index]")
                    if (itemValidation != null) {
                        return itemValidation
                    }
                }
            }
        }
        return null
    }

    private fun matchesType(value: Any, type: String): Boolean {
        return when (type) {
            "string" -> value is String
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object" -> value is Map<*, *>
            "array" -> value is List<*>
            else -> true
        }
    }

    private fun invalidParams(toolName: String, reason: String): McpValidationResult.Invalid {
        return McpValidationResult.Invalid(
            errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            message = "$toolName failed. Reason: $reason.",
        )
    }

    private fun formatNumber(number: Double): String {
        return if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
    }
}

sealed class McpValidationResult {
    data object ToolsList : McpValidationResult()

    data class ToolsCall(
        val toolName: String,
        val arguments: Map<String, Any?>,
        val projectDir: String,
    ) : McpValidationResult()

    data class Invalid(
        val errorCode: String,
        val message: String,
        val isJsonRpcError: Boolean = false,
        val jsonRpcCode: Int = McpJsonRpc.ErrorCode.InternalError,
    ) : McpValidationResult()
}
