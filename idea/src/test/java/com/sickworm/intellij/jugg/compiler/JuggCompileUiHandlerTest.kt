package com.sickworm.intellij.jugg.compiler

import com.android.tools.idea.run.IdeService
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.deploy.run.JuggInstallSession.PromptAction
import com.sickworm.intellij.jugg.compiler.ui.TooManyChangesConfirmResult
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito

class JuggCompileUiHandlerTest {

    @Test
    fun `manual downgrade confirmation preserves data or uninstalls only as selected`() {
        val application = Mockito.mock(Application::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.`when`(application).invokeAndWait(Mockito.any(Runnable::class.java))
        val choices = mapOf(
            ConfirmResult.POSITIVE to PromptAction.ALLOW_DOWNGRADE,
            ConfirmResult.LEFT to PromptAction.UNINSTALL,
            ConfirmResult.NEGATIVE to PromptAction.CANCEL,
            ConfirmResult.CANCEL to PromptAction.CANCEL,
        )

        Mockito.mockStatic(ApplicationManager::class.java).use { applicationManager ->
            applicationManager.`when`<Application> { ApplicationManager.getApplication() }.thenReturn(application)
            val handler = createHandler()
            for ((choice, expected) in choices) {
                Mockito.mockConstruction(CommonConfirmDialog::class.java) { dialog, _ ->
                    Mockito.`when`(dialog.showAndGet()).thenReturn(choice == ConfirmResult.POSITIVE)
                    Mockito.`when`(dialog.isClickCloseButton).thenReturn(choice == ConfirmResult.CANCEL)
                    Mockito.`when`(dialog.isClickLeftButton).thenReturn(choice == ConfirmResult.LEFT)
                }.use {
                    Assert.assertEquals(expected, handler.confirmDeployPrompt("INSTALL_FAILED_VERSION_DOWNGRADE"))
                }
            }
        }
    }

    @Test
    fun `rpc installation errors automatically authorize uninstall without a dialog`() {
        val handler = createHandler(isRpcMode = true)

        for (message in listOf("INSTALL_FAILED_VERSION_DOWNGRADE", "INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            Assert.assertEquals(PromptAction.UNINSTALL, handler.confirmDeployPrompt(message))
        }
    }

    @Test
    fun `other installation errors preserve the existing uninstall confirmation`() {
        val handler = createHandler()
        val message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

        for (confirmed in listOf(false, true)) {
            Mockito.mockConstruction(IdeService::class.java) { service, _ ->
                Mockito.`when`(service.prompt(message)).thenReturn(confirmed)
            }.use {
                val expected = if (confirmed) PromptAction.UNINSTALL else PromptAction.CANCEL
                Assert.assertEquals(expected, handler.confirmDeployPrompt(message))
            }
        }
    }

    private fun createHandler(
        isRpcMode: Boolean = false,
        isGradleCacheRefreshRequested: Boolean = false,
        testEventSinkFactory: ((String, Boolean) -> ((InstrumentationEvent) -> Unit)?)? = null,
    ): JuggCompileUiHandler {
        val project = Mockito.mock(Project::class.java)
        val options = Mockito.mock(JuggGradleCompileOptions::class.java)
        return JuggCompileUiHandler(
            project = project,
            isForceGradleCompile = false,
            isRpcMode = isRpcMode,
            juggGradleCompileOptions = options,
            logger = Logger.getInstance("JuggCompileUiHandlerTest"),
            isGradleCacheRefreshRequested = isGradleCacheRefreshRequested,
            testEventSinkFactory = testEventSinkFactory,
        )
    }

    @Test
    fun `confirmFallbackWhenNoFileChanges returns NEGATIVE in androidTest scenario`() {
        val handler = createHandler(
            testEventSinkFactory = { _, _ -> null }
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

    @Test
    fun `confirmTooManyChanges returns FALLBACK in rpc mode`() {
        val handler = createHandler(isRpcMode = true)
        val result = handler.confirmTooManyChanges(TooManyChangesInfo(0, 1, 1))
        Assert.assertEquals(TooManyChangesConfirmResult.FALLBACK, result)
    }


    @Test
    fun `IDE preference disables the too many changes dialog`() {
        val old = com.sickworm.intellij.jugg.ide.bean.JuggSettings.isConfirmFallbackWhenTooManyChanges
        try {
            com.sickworm.intellij.jugg.ide.bean.JuggSettings.isConfirmFallbackWhenTooManyChanges = false
            Assert.assertEquals(TooManyChangesConfirmResult.FALLBACK,
                createHandler().confirmTooManyChanges(TooManyChangesInfo(0, 1, 1)))
        } finally {
            com.sickworm.intellij.jugg.ide.bean.JuggSettings.isConfirmFallbackWhenTooManyChanges = old
        }
    }

    @Test
    fun `default handler is not rpc mode`() {
        Assert.assertFalse(CompileUiHandler.DEFAULT.isRpcMode)
    }

    @Test
    fun `jugg handler exposes rpc mode`() {
        Assert.assertTrue(createHandler(isRpcMode = true).isRpcMode)
        Assert.assertFalse(createHandler(isRpcMode = false).isRpcMode)
    }

    @Test
    fun `force gradle handler keeps cache refresh request`() {
        Assert.assertTrue(createHandler(isGradleCacheRefreshRequested = true).isGradleCacheRefreshRequested)
    }

    @Test
    fun `confirmDependencyChanges returns POSITIVE in rpc mode`() {
        val result = createHandler(isRpcMode = true).confirmDependencyChanges(DependencyDiffResultSet.createEmpty())

        Assert.assertEquals(ConfirmResult.POSITIVE, result)
    }

    @Test
    fun `onDeployUiMessage should write stdout in IDE mode`() {
        val handler = createHandler(isRpcMode = false)
        val captured = mutableListOf<String>()
        handler.processHandler = object : IProcessHandler {
            override var isCanceledByNextTask: Boolean = false
            override val isCanceled: Boolean = false
            override var cancelAction: (() -> Unit)? = null
            override fun notifyTextAvailable(text: String, outputType: com.intellij.openapi.util.Key<*>) {
                if (outputType == ProcessOutputType.STDOUT) captured.add(text)
            }
            override fun detachProcess() = Unit
            override fun destroyProcess() = Unit
        }

        handler.onDeployUiMessage("Android Test Results Matrix")

        Assert.assertTrue(captured.any { it.contains("Android Test Results Matrix") })
    }
}
