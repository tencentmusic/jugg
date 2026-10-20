package com.sickworm.intellij.jugg.gradle.compile

/** Contains only local tool diagnostics; never remote authentication or process output. */
class WindowsRsyncSetupException(
    val category: String,
    val toolPath: String = "",
    val missingTools: List<String> = emptyList(),
    val safeReason: String = "",
) : IllegalStateException("Windows rsync setup failed: $category. " +
        "Selected tool: ${toolPath.ifEmpty { "none" }}. Missing: ${missingTools.joinToString()}. $safeReason " +
        "Install Cygwin rsync and openssh, add its bin directory to user PATH, and restart Android Studio. " +
        "Complete the first rsync installation or adjust PATH priority.")
