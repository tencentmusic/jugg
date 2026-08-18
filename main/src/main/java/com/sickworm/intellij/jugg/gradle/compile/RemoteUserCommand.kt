package com.sickworm.intellij.jugg.gradle.compile

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Executes user-provided shell text in an isolated child shell with a unique completion marker. */
class RemoteUserCommand(
    private val remoteProjectPath: String,
    command: String,
) : ISshCommand {

    private val encodedCommand = Base64.getEncoder()
        .encodeToString(command.toByteArray(StandardCharsets.UTF_8))
    private val resultPrefix = "__JUGG_REMOTE_COMMAND_${UUID.randomUUID().toString().replace("-", "")}__="

    override fun getCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        require(!isWindows) { "Remote user commands only support Unix shells." }
        val languagePrefix = if (isNeedSetChineseLanguage) "export LC_CTYPE=\"zh_CN.utf8\" ; " else ""
        return languagePrefix +
            "__jugg_command=\$(printf '%s' '$encodedCommand' | base64 -d) ; __jugg_result=\$? ; " +
            "if [ \"\$__jugg_result\" -eq 0 ]; then " +
            "(cd ${shellQuote(remoteProjectPath)} && \"\${SHELL:-/bin/sh}\" -c \"\$__jugg_command\") ; " +
            "__jugg_result=\$? ; fi ; printf '\\n${resultPrefix}%s\\n' \"\$__jugg_result\""
    }

    override fun hasFinishWithResult(terminalOutputLine: String): Int? {
        if (!terminalOutputLine.startsWith(resultPrefix)) {
            return null
        }
        return terminalOutputLine.substring(resultPrefix.length).trim().toIntOrNull()
    }

    override fun isCanOutput(line: String, isError: Boolean): Boolean {
        return !line.contains(resultPrefix)
    }

    override fun getPrintSafeCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean) = "(secure)"

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
