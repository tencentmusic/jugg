package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito

class JuggCompileUiHandlerTest {

    private fun createHandler(
        isRpcMode: Boolean = false,
        testEventSinkFactory: ((String) -> ((InstrumentationEvent) -> Unit)?)? = null,
    ): JuggCompileUiHandler {
        val project = Mockito.mock(Project::class.java)
        val options = Mockito.mock(JuggGradleCompileOptions::class.java)
        return JuggCompileUiHandler(
            project = project,
            isForceGradleCompile = false,
            isRpcMode = isRpcMode,
            juggGradleCompileOptions = options,
            logger = Logger.getInstance("JuggCompileUiHandlerTest"),
            testEventSinkFactory = testEventSinkFactory,
        )
    }

    @Test
    fun `confirmFallbackWhenNoFileChanges returns NEGATIVE in androidTest scenario`() {
        val handler = createHandler(
            testEventSinkFactory = { null }
        )
        val result = handler.confirmFallbackWhenNoFileChanges()
        Assert.assertEquals(ConfirmResult.NEGATIVE, result)
    }

    @Test
    fun `confirmFallbackWhenNoFileChanges returns NEGATIVE in rpc mode`() {
        val handler = createHandler(isRpcMode = true)
        val result = handler.confirmFallbackWhenNoFileChanges()
        Assert.assertEquals(ConfirmResult.NEGATIVE, result)
    }
}
