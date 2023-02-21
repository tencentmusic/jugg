package com.sickworm.intellij.jugg.remote

/**
 * Exec a command to ssh terminal
 */
interface ISshCommand {

    val command: String

    /**
     * @param terminalOutputLine the output ends with '\n' and without '\n' from terminal
     * @return the input to terminal, null if won't input
     */
    fun getInput(terminalOutputLine: String): String? = null

    /**
     * @param terminalOutputLine the output ends with '\n' and without '\n' from terminal
     * @return the result of command, null if not reach end
     */
    fun hasFinishWithResult(terminalOutputLine: String): Int?

}

abstract class BaseSshCommand : ISshCommand {

    abstract val baseCommand: String

    /**
     * add echo at last to confirm exec finished and get the result
     * '\n' to avoid control ascii code on the line start
     */
    override val command: String get() = "$baseCommand ; echo \"\n$RESULT_ECHO\$?\""

    override fun hasFinishWithResult(terminalOutputLine: String): Int? {
        if (terminalOutputLine.startsWith(RESULT_ECHO)) {
            return terminalOutputLine.substring(RESULT_ECHO.length).toInt()
        }
        return null
    }

    companion object {
        private const val RESULT_ECHO = "[Jugg SSH Result]: "
    }
}


class LoginIftCommand : BaseSshCommand() {

    override val baseCommand: String = """ift_i="${'$'}HOME/ift_install.sh"; export PATH="${'$'}PATH:${'$'}HOME/.ft"; for i in 9.139.66.141 9.139.66.142 9.139.66.133 10.28.37.11 10.28.37.12 10.123.119.236 10.123.119.237 9.134.41.119 9.134.115.237 9.135.114.22 9.135.114.23; do curl -v -fksSL --connect-timeout 1 --noproxy '*' -o "${'$'}ift_i" http://${'$'}i/install && break; done ; test -s "${'$'}ift_i" && bash "${'$'}ift_i" || echo failed to install ft"""
    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine == "Login With User:") {
            return "1"
        }
        return null
    }

}

class SyncFileCommand(
    localProjectPath: String,
    serverProjectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """ft sync -s $localProjectPath --get $serverProjectPath -a "-av --delete  --exclude='build/' --exclude='imagebus/log/' --exclude='imagebus/mapping/' --exclude='local.properties' --exclude='.gradle/' --exclude='.idea/'  --exclude='buildSrc/.gradle/' --exclude='*.iml' --exclude='.git/objects/'" """

    override fun getInput(terminalOutputLine: String): String? {
        if (terminalOutputLine == "Login With User:") {
            return "1"
        }
        return null
    }
}

class CompileProjectCommand(
    serverProjectPath: String,
) : BaseSshCommand() {

    override val baseCommand: String = """cd $serverProjectPath && ./gradlew :app:assembleDebug --console=plain"""
}

