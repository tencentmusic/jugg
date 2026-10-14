package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.isMac
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue

class ExpectCommandTest {

    @Test
    fun bashVariables_shouldBeExpandedByBash() {
        assumeTrue(isMac)
        val command = "true ; __jugg_exit=\$? ; echo \"result: \$__jugg_exit\""

        val process = ProcessBuilder(*buildExpectCommand(command, "unused")).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        process.waitFor()
        assertTrue(output.contains("result: 0"), "output=$output, error=$error")
    }

    @Test
    fun passwordWithTclCharacters_shouldReachBashUnchanged() {
        assumeTrue(isMac)
        val password = "p\$[a]\"\\word"
        val command = "printf 'Password:'; IFS= read -r value; printf '\\nreceived=%s\\n' \"\$value\""

        val process = ProcessBuilder(*buildExpectCommand(command, password)).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        process.waitFor()
        assertTrue(output.contains("received=$password"), "output=$output, error=$error")
    }
}
