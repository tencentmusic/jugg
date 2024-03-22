package com.sickworm.intellij.jugg.gradle.compile

/**
 * Exec a command to ssh terminal
 */
interface ISshCommand {

    fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String

    /**
     * call be fore invoke [getCommand].
     */
    fun beforeInvokeCommand() = Unit

    /**
     * @param terminalOutputLine the output ends with '\n' and without '\n' from terminal
     * @return the input to terminal, null if we won't input
     */
    fun getInput(terminalOutputLine: String): String? = null

    /**
     * @param terminalOutputLine the output ends with '\n' and without '\n' from terminal
     * @return the result of command, null if not reach end
     */
    fun hasFinishWithResult(terminalOutputLine: String): Int?

    /**
     * @return should interrupt like receiving user input Username:, Pin+Token:
     */
    fun shouldInterrupted(currentChar: Int, buffer: StringBuilder): Int? = null
}

abstract class BaseSshCommand : ISshCommand {

    abstract val baseCommand: String

    /**
     * add echo at last to confirm exec finished and get the result
     * '\n' to avoid control ascii code on the line start
     */
    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        val fixedBaseCommand = if (isWindows) {
            baseCommand.replace("./gradlew", ".\\gradlew.bat")
        } else {
            baseCommand
        }
        val command = if (isWindows) {
            val escapeResultEcho = RESULT_ECHO.replace("(", "^(").replace(")", "^)")
            "$fixedBaseCommand && (echo. & echo ${escapeResultEcho}0& echo.) || (echo. & echo ${escapeResultEcho}1& echo.)"
        } else {
            "$fixedBaseCommand ; echo \"\n$RESULT_ECHO\$?\n\""
        }
        if (isNeedSetChineseLanguage && !isWindows) {
            return "export LC_CTYPE=\"zh_CN.utf8\" ; $command"
        }
        return command
    }

    override fun hasFinishWithResult(terminalOutputLine: String): Int? {
        if (terminalOutputLine.startsWith(RESULT_ECHO) && !terminalOutputLine.endsWith("?")) {
            return terminalOutputLine.substring(RESULT_ECHO.length).toInt()
        }
        return null
    }

    companion object {
        private const val RESULT_ECHO = "(Jugg) command result: "
    }
}


