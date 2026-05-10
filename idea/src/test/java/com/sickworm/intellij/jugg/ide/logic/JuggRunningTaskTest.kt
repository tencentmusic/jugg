package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import org.junit.Test
import org.mockito.Mockito

class JuggRunningTaskTest {

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
}
