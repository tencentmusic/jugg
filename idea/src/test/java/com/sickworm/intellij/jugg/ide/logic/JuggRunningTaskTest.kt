package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class JuggRunningTaskTest {

    @Test
    fun `compile event title should distinguish selected path and no-op compile`() {
        assertEquals("Incremental compile completed", buildCompileEventTitle(false, true, false, true))
        assertEquals("Gradle compile completed", buildCompileEventTitle(true, true, false, true))
        assertEquals("Incremental compile failed", buildCompileEventTitle(false, false, false, true))
        assertEquals("Gradle compile failed", buildCompileEventTitle(true, false, false, true))
        assertEquals("Incremental compile canceled", buildCompileEventTitle(false, false, true, true))
        assertEquals("No compile needed", buildCompileEventTitle(false, true, false, false))
    }

    @Test
    fun `gradle compile install success uses BUILD_AND_INSTALL headline`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.INSTALL,
            isGradleCompile = true,
            totalTimeMillis = 13_000,
        )

        assertEquals("\nGradle BUILD_AND_INSTALL SUCCESSFUL in 13s.", lines.headline)
        assertEquals("App launched.", lines.followUp)
    }

    @Test
    fun `incremental recover install success uses Jugg INSTALL headline`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.INSTALL,
            isGradleCompile = false,
            totalTimeMillis = 2_827,
        )

        assertEquals("\nJugg INSTALL SUCCESSFUL in 2s.", lines.headline)
        assertEquals("App launched.", lines.followUp)
    }

    @Test
    fun `incremental hot reload success keeps Jugg deploy headline`() {
        val lines = buildDeploySuccessLogLines(
            deployType = JuggDeployData.DeployType.HOT_RELOAD,
            isGradleCompile = false,
            totalTimeMillis = 5_000,
        )

        assertEquals("\nJugg HOT_RELOAD SUCCESSFUL in 5s.", lines.headline)
        assertEquals("App deployed.", lines.followUp)
    }

    @Test
    fun `first task start creates run tool window without activating it`() {
        val handler = Mockito.mock(CompileUiHandler::class.java)

        prepareRunToolWindowOnTaskStart(isFirstTimeRun = true, handler)

        Mockito.verify(handler).ensureRunWindowCreated()
        Mockito.verify(handler, Mockito.never()).showRunWindow()
    }

    @Test
    fun `non-first task start does not touch run tool window`() {
        val handler = Mockito.mock(CompileUiHandler::class.java)

        prepareRunToolWindowOnTaskStart(isFirstTimeRun = false, handler)

        Mockito.verify(handler, Mockito.never()).ensureRunWindowCreated()
        Mockito.verify(handler, Mockito.never()).showRunWindow()
    }

    @Test
    fun `normal run detaches process when task stops`() {
        assertTrue(shouldDetachProcessOnTaskStop(isProcessCanceled = false))
    }

    @Test
    fun `debug run detaches jugg process when task stops`() {
        assertTrue(shouldDetachProcessOnTaskStop(isProcessCanceled = false))
    }

    @Test
    fun `canceled run does not detach process again when task stops`() {
        assertFalse(shouldDetachProcessOnTaskStop(isProcessCanceled = true))
    }
}
