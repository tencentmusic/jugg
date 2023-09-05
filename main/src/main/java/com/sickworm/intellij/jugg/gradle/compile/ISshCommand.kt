package com.sickworm.intellij.jugg.gradle.compile

/**
 * Exec a command to ssh terminal
 */
interface ISshCommand {

    fun getCommend(isNeedSetChineseLanguage: Boolean): String

    /**
     * call be fore invoke [getCommend].
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

abstract class BaseSshCommand() : ISshCommand {

    abstract val baseCommand: String

    /**
     * add echo at last to confirm exec finished and get the result
     * '\n' to avoid control ascii code on the line start
     */
    override fun getCommend(isNeedSetChineseLanguage: Boolean): String {
        return if (isNeedSetChineseLanguage) {
            "export LC_CTYPE=\"zh_CN.utf8\" ; $baseCommand ; echo \"\n$RESULT_ECHO\$?\n\""
        } else {
            "$baseCommand ; echo \"\n$RESULT_ECHO\$?\n\""
        }
    }

    override fun hasFinishWithResult(terminalOutputLine: String): Int? {
        if (terminalOutputLine.startsWith(RESULT_ECHO) && !terminalOutputLine.endsWith("?")) {
            return terminalOutputLine.substring(RESULT_ECHO.length).toInt()
        }
        return null
    }

    companion object {
        private const val RESULT_ECHO = "[Jugg] command result: "
    }
}


