package com.sickworm.intellij.jugg.gradle.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JschShellTerminalHelperTest {

    @Test
    fun respondToCursorPositionQuery() {
        val buffer = StringBuilder("\u001B[6n")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.CURSOR_POSITION_REPORT, response)
        assertEquals("", buffer.toString())
    }

    @Test
    fun respondToOsc11QueryWithStTerminator() {
        val buffer = StringBuilder("\u001B]11;?\u001B\\")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.BACKGROUND_COLOR_REPORT, response)
        assertEquals("", buffer.toString())
    }

    @Test
    fun respondToOsc11QueryWithBelTerminator() {
        val buffer = StringBuilder("\u001B]11;?\u0007")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.BACKGROUND_COLOR_REPORT, response)
        assertEquals("", buffer.toString())
    }

    @Test
    fun respondToQueriesBeforePromptLikeUserCancelOutput() {
        val buffer = StringBuilder("\u001B]11;?\u001B\\\u001B[6n[root@host ~]# ")
        var responses = mutableListOf<String>()
        while (true) {
            val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer) ?: break
            responses.add(response)
        }
        assertEquals(
            listOf(
                JschShellTerminalHelper.BACKGROUND_COLOR_REPORT,
                JschShellTerminalHelper.CURSOR_POSITION_REPORT,
            ),
            responses,
        )
        assertEquals("[root@host ~]# ", buffer.toString())
    }

    @Test
    fun respondToOsc11QueryWithoutTerminatorBeforePrompt() {
        val buffer = StringBuilder("\u001B]11;?[root@yangggzhou-2ix20jwbsa ~]# ")
        val response = JschShellTerminalHelper.tryRespondTerminalQuery(buffer)
        assertEquals(JschShellTerminalHelper.BACKGROUND_COLOR_REPORT, response)
        assertEquals("[root@yangggzhou-2ix20jwbsa ~]# ", buffer.toString())
    }

    @Test
    fun incompleteOsc11QueryDoesNotRespond() {
        val buffer = StringBuilder("\u001B]11;?")
        assertNull(JschShellTerminalHelper.tryRespondTerminalQuery(buffer))
        assertEquals("\u001B]11;?", buffer.toString())
    }

    @Test
    fun parseShellReadyResultLine() {
        assertEquals(0, JschShellTerminalHelper.parseShellReadyResult("(Jugg) ShellReady result: 0"))
        assertNull(JschShellTerminalHelper.parseShellReadyResult("(Jugg) ShellReady result: \$?"))
        assertNull(JschShellTerminalHelper.parseShellReadyResult("(Jugg) MkDirCommand result: 0"))
    }

    @Test
    fun shellReadyProbeCommandUsesSingleLineEcho() {
        assertTrue(JschShellTerminalHelper.SHELL_READY_PROBE_COMMAND.contains("(Jugg) ShellReady result: \$?"))
        assertTrue(!JschShellTerminalHelper.SHELL_READY_PROBE_COMMAND.contains("\n"))
    }
}
