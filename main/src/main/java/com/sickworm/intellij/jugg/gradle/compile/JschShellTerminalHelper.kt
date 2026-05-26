package com.sickworm.intellij.jugg.gradle.compile

/**
 * Respond to PTY terminal queries from remote shell so JSch shell behaves like a real ssh client.
 * Without these responses, tlinux/devcloud prompts may never become ready and stdin is not executed.
 */
object JschShellTerminalHelper {

    private const val ESC = '\u001B'

    /** Cursor position report for DECRQCPR (`ESC [ 6 n`). */
    const val CURSOR_POSITION_REPORT = "\u001B[1;1R"

    /** Default background color report for OSC 11 query. */
    const val BACKGROUND_COLOR_REPORT = "\u001B]11;rgb:0000/0000/0000\u001B\\"

    const val SHELL_READY_RESULT_ECHO = "(Jugg) ShellReady result: "

    /** Probe command to verify the remote shell accepts stdin and can execute commands. */
    const val SHELL_READY_PROBE_COMMAND = "echo ; echo \"${SHELL_READY_RESULT_ECHO}\$?\""

    /**
     * Detect a completed terminal query in [buffer] and return the response.
     * Matched query bytes are removed from [buffer].
     */
    fun tryRespondTerminalQuery(buffer: StringBuilder): String? {
        val text = buffer.toString()
        val cursorQueryIndex = text.indexOf("$ESC[6n")
        val osc11Index = text.indexOf("$ESC]11;?")

        if (osc11Index >= 0 && (cursorQueryIndex < 0 || osc11Index < cursorQueryIndex)) {
            val queryEnd = osc11Index + 6
            val stTerminator = text.indexOf("$ESC\\", osc11Index)
            val belTerminator = text.indexOf('\u0007', osc11Index)
            val endIndex = when {
                stTerminator >= 0 -> stTerminator + 2
                belTerminator >= 0 -> belTerminator + 1
                // Some servers print the prompt immediately after OSC 11 without ST/BEL.
                queryEnd < text.length -> queryEnd
                else -> return null
            }
            buffer.delete(0, endIndex)
            return BACKGROUND_COLOR_REPORT
        }

        if (cursorQueryIndex >= 0) {
            buffer.delete(0, cursorQueryIndex + 4)
            return CURSOR_POSITION_REPORT
        }
        return null
    }

    fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)"), "")
    }

    fun parseShellReadyResult(line: String): Int? {
        if (line.startsWith(SHELL_READY_RESULT_ECHO) && !line.endsWith("?")) {
            return line.substring(SHELL_READY_RESULT_ECHO.length).toIntOrNull()
        }
        return null
    }
}
