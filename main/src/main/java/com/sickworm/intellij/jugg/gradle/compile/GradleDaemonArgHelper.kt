package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.ide.bean.JuggSettings

/**
 * Helper to provide `--no-daemon` argument for Gradle fallback compilation.
 * Prevents argument duplication when `--no-daemon` is already present in the command.
 */
object GradleDaemonArgHelper {

    const val NO_DAEMON_ARG = "--no-daemon"
    private val NO_DAEMON_REGEX = Regex("""(?<=\s|^)--no-(daemon|deamon)(?=\s|$)""", RegexOption.IGNORE_CASE)

    /**
     * Returns `" --no-daemon"` if enabled and not already specified in [compileCommand].
     */
    fun getDisableArgsIfEnabled(
        compileCommand: String,
        isNoDaemon: Boolean = JuggSettings.isGradleCompileNoDaemon,
    ): String {
        if (!isNoDaemon || compileCommand.contains(NO_DAEMON_REGEX)) {
            return ""
        }
        return " $NO_DAEMON_ARG"
    }
}
