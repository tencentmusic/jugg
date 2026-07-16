package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.deploy.FullBuildInfoSerializer
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestSourceParseException
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestSourceParser
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestSourceSelection
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File

/**
 * InstrumentMcpToolAction implements MCP tool `instrument`.
 *
 * The tool is source-file anchored: [AndroidTestRunSpec.sourcePath] identifies
 * the androidTest source set and target test APK, while class/method only filter
 * tests inside that source file.
 */
class InstrumentMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.INSTRUMENT

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Run androidTest from a source file anchor via Jugg compile/deploy pipeline.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "sourcePath" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Test source file path under src/androidTest. Relative paths are resolved against projectDir.",
                ),
                "class" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Fully-qualified test class in sourcePath. Optional when the file has one test class.",
                ),
                "method" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Test method inside the resolved class.",
                ),
                "runner" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Instrumentation runner override.",
                ),
                "extras" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Additional am instrument -e key value pairs. Values must be strings.",
                    additionalProperties = true,
                ),
            ),
            required = listOf("projectDir", "sourcePath"),
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
        val baselineError = validateAndroidTestBaseline(arguments)
        if (baselineError != null) {
            return baselineError
        }
        return CompileAndDeployMcpToolAction.deployAction(
            runtime = runtime,
            toolName = toolName,
            isSkipDeploy = false,
            isAlwaysRestartApp = false,
            androidTestRunSpec = spec,
            buildTargetOverride = BuildTarget.ANDROID_TEST,
            waitAppReadyAfterSuccess = false,
        )
    }

    private fun parseSpec(arguments: Map<String, Any?>): ParseSpecResult {
        val sourcePath = firstNonBlankString(arguments, "sourcePath")
        if (sourcePath.isEmpty()) {
            return ParseSpecResult(error = invalidParams(
                "sourcePath is required for Jugg instrument. " +
                    "Pass a test file under src/androidTest, for example: " +
                    "jugg instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt"
            ))
        }
        for (key in listOf("package", "testPackage", "testsRegex", "regex")) {
            if (arguments.containsKey(key)) {
                return ParseSpecResult(error = invalidParams("$key is not supported. Use sourcePath plus class/method"))
            }
        }
        for (key in listOf("clazz", "instrumentationRunner", "e")) {
            if (arguments.containsKey(key)) {
                return ParseSpecResult(error = invalidParams("$key is not supported. Use class/runner/extras"))
            }
        }
        val classArg = firstNonBlankString(arguments, "class")
        val methodArg = firstNonBlankString(arguments, "method").ifEmpty { null }
        val runner = firstNonBlankString(arguments, "runner").ifEmpty { null }
        val selection = try {
            resolveSourceSelection(
                projectDir = firstNonBlankString(arguments, "projectDir"),
                sourcePath = sourcePath,
                requestedClass = classArg.ifEmpty { null },
                requestedMethod = methodArg,
            )
        } catch (e: AndroidTestSourceParseException) {
            return ParseSpecResult(error = invalidParams(e.message.orEmpty()))
        }

        val extras = mutableListOf<Pair<String, String>>()

        @Suppress("UNCHECKED_CAST")
        val rawExtras = arguments["extras"] as? Map<String, Any?>
        val mergedExtras = linkedMapOf<String, Any?>()
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
                testClass = selection.testClass,
                testMethod = selection.testMethod,
                extraArgs = extras,
                runnerOverride = runner,
                sourcePath = sourcePath,
            ),
        )
    }

    private fun validateAndroidTestBaseline(arguments: Map<String, Any?>): McpToolResult? {
        val projectDir = firstNonBlankString(arguments, "projectDir")
        if (projectDir.isEmpty()) {
            return null
        }
        val projectDirFile = File(projectDir)
        if (!projectDirFile.exists()) {
            return null
        }
        if (isAndroidTestBaselineEnabled(projectDirFile)) {
            return null
        }
        return invalidParams(
            "enabledAndroidTest=false. The latest persisted full-build baseline was not built with AndroidTest target. " +
                "Open the Jugg App Run Configuration in Android Studio / IntelliJ, Enable Android Test / enableAndroidTest, " +
                "run that Jugg configuration once with a full build / jugg gradle-build, then re-run " +
                "jugg --console=json status and continue after data.enabledAndroidTest=true."
        )
    }

    private fun isAndroidTestBaselineEnabled(projectDir: File): Boolean {
        val fullBuildInfoFile = File(JuggPathManager(projectDir).compileContextDbDir, "full_build_info.json")
        if (!fullBuildInfoFile.exists()) {
            return false
        }
        return runCatching {
            FullBuildInfoSerializer().deserialize(fullBuildInfoFile.readText(Charsets.UTF_8)).buildTarget == BuildTarget.ANDROID_TEST
        }.getOrDefault(false)
    }

    private fun resolveSourceSelection(
        projectDir: String,
        sourcePath: String,
        requestedClass: String?,
        requestedMethod: String?,
    ): AndroidTestSourceSelection {
        val sourceFile = File(sourcePath).let { if (it.isAbsolute) it else File(projectDir, sourcePath) }
        if (!sourceFile.exists()) {
            return AndroidTestSourceSelection(
                testClass = requestedClass,
                testMethod = requestedMethod,
            )
        }
        return AndroidTestSourceParser.resolve(sourceFile, requestedClass, requestedMethod)
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
