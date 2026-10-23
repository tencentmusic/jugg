package com.sickworm.intellij.jugg.gradle.compile

/** Contains only local tool diagnostics; never remote authentication or process output. */
class WindowsRsyncSetupException(
    val category: String,
    val toolPath: String = "",
    val missingTools: List<String> = emptyList(),
    val safeReason: String = "",
) : IllegalStateException("Windows rsync setup failed: $category. " +
        "Selected tool: ${toolPath.ifEmpty { "none" }}. " +
        (if (missingTools.isEmpty()) "" else "Missing: ${missingTools.joinToString()}. ") +
        (if (safeReason.isEmpty()) "" else "$safeReason ") + when (category) {
            "rsync_not_found", "missing_tools" ->
                "Install or complete Cygwin rsync and openssh, add its bin directory to user PATH, " +
                        "and restart Android Studio. Check PATH priority if another installation is selected."
            "tool_probe_failed" ->
                "Run the selected tool with the probe argument above to inspect the failure, then retry. " +
                        "A failed probe does not by itself mean Cygwin is missing."
            "tool_start_failed" ->
                "Check the selected tool's reported startup error, access permissions, and runtime dependencies, then retry."
            "path_conversion_failed" ->
                "Check the selected cygpath tool and its path conversion result, then retry."
            else -> "Check the reported local tool failure before changing the installation."
        })
