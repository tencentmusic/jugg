package com.sickworm.intellij.jugg.mock

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.SqlApkFileDatabase
import com.android.tools.deployer.tasks.TaskRunner
import com.android.tools.idea.run.DeploymentService
import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.NameMapper
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.impl.DebuggerManagerImpl
import com.intellij.execution.RunManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.mock.MockProject
import com.intellij.mock.MockRunManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiClass
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.nio.file.Paths
import java.util.concurrent.Executors

class JuggMockProject: MockProject(null, {}) {

    private val runManager = MockRunManager()

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

        return@run deploymentService
    }

    private val debuggerManager = DebuggerManagerImpl(this)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getService(serviceClass: Class<T>): T? {
        if (serviceClass == RunManager::class.java) {
            return runManager as T
        } else if (serviceClass == DeploymentService::class.java) {
            return deploymentService as T
        } else if (serviceClass == DebuggerManager::class.java) {
            return debuggerManager as T
        }
        return super.getService(serviceClass)
    }
}