package com.sickworm.intellij.jugg.mock

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.SqlApkFileDatabase
import com.android.tools.deployer.tasks.TaskRunner
import com.android.tools.idea.run.DeploymentService
import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.impl.DebuggerManagerImpl
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.intellij.mock.MockRunManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.wm.*
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import java.io.File
import java.nio.file.Paths

class JuggMockProject(private val basePath: File): MockProject(null, {}) {

    private val runManager = mock(MockRunManager::class.java).also {
        val settings = mock(RunnerAndConfigurationSettings::class.java)
        doReturn(settings).`when`(it)
            .createConfiguration(anyString(), any<ConfigurationFactory>())
    }

    private val deploymentService = run {
        val deploymentService = mock(DeploymentService::class.java)

        val service = SyncExecutorService()
        val runner = TaskRunner(service)
        `when`(deploymentService.taskRunner).thenReturn(runner)

        val dexDbPath = Paths.get(PathManager.getSystemPath(), ".dex_cache.db")
        val deployDbPath = Paths.get(PathManager.getSystemPath(), ".deploy_cache.db")
        val dexDatabase = NotNullLazyValue.createValue {
            SqlApkFileDatabase(
                dexDbPath.toFile(),
                PathManager.getTempPath()
            )
        }
        `when`(deploymentService.dexDatabase).thenReturn(dexDatabase.value)

        val deploymentCacheDatabase = NotNullLazyValue.createValue {
            DeploymentCacheDatabase(
                deployDbPath.toFile()
            )
        }
        `when`(deploymentService.deploymentCacheDatabase).thenReturn(deploymentCacheDatabase.value)

        registerService(PropertiesComponent::class.java, DummyPropertiesComponent())

        return@run deploymentService
    }

    @Suppress("OVERRIDE_DEPRECATION", "UnstableApiUsage")
    private val toolWindowManager = object : ToolWindowManager() {
        override val activeToolWindowId: String? get() = null
        override val focusManager: IdeFocusManager = mock(IdeFocusManager::class.java)
        override val isEditorComponentActive: Boolean = false
        override val lastActiveToolWindowId: String? = null
        override val toolWindowIdSet: Set<String> = emptySet()
        override val toolWindowIds: Array<String> = emptyArray()

        override fun activateEditorComponent() {
        }

        override fun canShowNotification(toolWindowId: String): Boolean {
            return false
        }

        override fun getToolWindow(id: String?): ToolWindow? {
            return null
        }

        override fun getToolWindowBalloon(id: String): Balloon? {
            return null
        }

        override fun invokeLater(runnable: Runnable) {
        }

        override fun isMaximized(window: ToolWindow): Boolean {
            return false
        }

        override fun notifyByBalloon(options: ToolWindowBalloonShowOptions) {
        }

        override fun registerToolWindow(task: RegisterToolWindowTask): ToolWindow {
            return mock(ToolWindow::class.java)
        }

        override fun setMaximized(window: ToolWindow, maximized: Boolean) {
        }

        override fun unregisterToolWindow(id: String) {
        }

    }

    override fun getName(): String {
        return basePath.name
    }

    private val debuggerManager = DebuggerManagerImpl(this)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getService(serviceClass: Class<T>): T? {
        return when (serviceClass) {
            RunManager::class.java -> {
                runManager as T
            }
            DeploymentService::class.java -> {
                deploymentService as T
            }
            DebuggerManager::class.java -> {
                debuggerManager as T
            }
            ToolWindowManager::class.java -> {
                toolWindowManager as T
            }
            else -> super.getService(serviceClass)
        }
    }

    override fun getBasePath(): String {
        return basePath.absolutePath
    }
}