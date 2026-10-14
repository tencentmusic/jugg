package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.gradle.project.model.AndroidModuleModel
import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.AndroidRunConfigurationType
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AsDeployerCompatDispatcherTest {

    @Test
    fun `continues polling when legacy run configuration lookup throws`() {
        assertPollingAfterLegacyLookup(IllegalStateException("legacy model unavailable"))
    }

    @Test
    fun `continues polling when legacy run configuration model is absent`() {
        assertPollingAfterLegacyLookup(null)
    }

    @Test
    fun `unsynced primary model keeps an empty result without legacy lookup`() {
        withRunConfiguration { module, project, logger ->
            Mockito.mockStatic(GradleAndroidModel::class.java).use { primaryModel ->
                Mockito.mockStatic(AndroidModuleModel::class.java).use { legacyModel ->
                    primaryModel.`when`<GradleAndroidModel?> {
                        GradleAndroidModel::class.java.getMethod("get", Module::class.java).invoke(null, module)
                    }.thenReturn(null)
                    val compat = Mockito.mock(ChipmunkAsDeployerCompat::class.java, Mockito.CALLS_REAL_METHODS)

                    assertEquals(emptyList<SuggestRunConfiguration>(),
                        compat.getSuggestRunConfigurations(emptyList(), project, logger, false))
                    legacyModel.verifyNoInteractions()
                }
            }
        }
    }

    private fun assertPollingAfterLegacyLookup(legacyFailure: Throwable?) {
        withRunConfiguration { module, project, logger ->
            val original = IncompatibleClassChangeError("GradleAndroidModel became an interface")
            Mockito.mockStatic(GradleAndroidModel::class.java).use { primaryModel ->
                Mockito.mockStatic(AndroidModuleModel::class.java).use { legacyModel ->
                    // The Chipmunk bytecode calls the Java static API, not the newer Kotlin companion.
                    primaryModel.`when`<GradleAndroidModel?> {
                        GradleAndroidModel::class.java.getMethod("get", Module::class.java).invoke(null, module)
                    }.thenThrow(original)
                    legacyModel.`when`<AndroidModuleModel?> {
                        AndroidModuleModel::class.java.getMethod("get", Module::class.java).invoke(null, module)
                    }.thenAnswer {
                        legacyFailure?.let { throw it }
                        null
                    }
                    val priority = Mockito.mock(ChipmunkAsDeployerCompat::class.java, Mockito.CALLS_REAL_METHODS)
                    val fallback = mock<IAsDeployerCompat>()
                    val expected = listOf(SuggestRunConfiguration("app", "./gradlew :app:assembleDebug",
                        "app/build/outputs/apk/debug/*.apk", "debug"))
                    whenever(fallback.getSuggestRunConfigurations(emptyList(), project, logger, false))
                        .thenReturn(expected)
                    val dispatcher = AsDeployerCompatDispatcher<IAsDeployerCompat>(
                        listOf(priority, fallback), priority, { it.javaClass.simpleName }, {}, {},
                    )

                    val result = dispatcher.invoke {
                        it.getSuggestRunConfigurations(emptyList(), project, logger, false)
                    }

                    assertEquals(expected, result)
                    if (legacyFailure != null) {
                        assertSame(legacyFailure, original.suppressed.single().cause)
                    }
                }
            }
        }
    }

    private fun withRunConfiguration(action: (Module, Project, Logger) -> Unit) {
        val module = mock<Module>()
        val project = mock<Project>()
        val logger = mock<Logger>()
        val runConfiguration = mock<AndroidRunConfiguration>()
        whenever(runConfiguration.modules).thenReturn(arrayOf(module))
        val settings = mock<RunnerAndConfigurationSettings>()
        whenever(settings.configuration).thenReturn(runConfiguration)
        whenever(settings.type).thenReturn(mock<AndroidRunConfigurationType>())
        val runManager = mock<RunManager>()
        whenever(runManager.allSettings).thenReturn(listOf(settings))
        whenever(project.getService(RunManager::class.java)).thenReturn(runManager)
        whenever(project.getUserData(RunManager.IS_RUN_MANAGER_INITIALIZED)).thenReturn(true)
        action(module, project, logger)
    }

    @Test
    fun `falls back to lower priority implementation for compat errors`() {
        val priority = FakeCompatImpl("priority", error = NoClassDefFoundError("missing deployer API"))
        val fallback = FakeCompatImpl("fallback")
        val dispatcher = AsDeployerCompatDispatcher(
            implementations = listOf(priority, fallback),
            priorityImplementation = priority,
            nameOf = { it.name },
            logDebug = {},
            logWarn = {},
        )

        val result = dispatcher.invoke { it.call() }

        assertEquals("fallback", result)
    }

    @Test
    fun `does not fall back for business errors`() {
        val priority = FakeCompatImpl("priority", error = IllegalStateException("install failed"))
        val fallback = FakeCompatImpl("fallback")
        val dispatcher = AsDeployerCompatDispatcher(
            implementations = listOf(priority, fallback),
            priorityImplementation = priority,
            nameOf = { it.name },
            logDebug = {},
            logWarn = {},
        )

        try {
            dispatcher.invoke { it.call() }
        } catch (e: IllegalStateException) {
            assertEquals("install failed", e.message)
            assertEquals(0, fallback.callCount)
            return
        }

        throw AssertionError("Expected business error")
    }

    @Test
    fun `fallback session remains bound to the implementation that created it`() {
        val priority = FakeCompatImpl("priority", error = NoSuchMethodError("priority deployer API"))
        val fallback = FakeCompatImpl("fallback")
        val dispatcher = AsDeployerCompatDispatcher(
            implementations = listOf(priority, fallback),
            priorityImplementation = priority,
            nameOf = { it.name },
            logDebug = {},
            logWarn = {},
        )

        val session = dispatcher.invoke { it.createSession() }

        assertSame(fallback, session.applyChangesExecutor)
        assertEquals(1, fallback.callCount)
    }

    private class FakeCompatImpl(
        val name: String,
        private val error: Throwable? = null,
    ) : IApplyChangesExecutor by mock() {
        var callCount = 0
            private set

        fun call(): String {
            callCount++
            error?.let { throw it }
            return name
        }

        fun createSession(): JuggInstallSession {
            call()
            return JuggInstallSession(this, mock(), name, { true }, {})
        }
    }
}
