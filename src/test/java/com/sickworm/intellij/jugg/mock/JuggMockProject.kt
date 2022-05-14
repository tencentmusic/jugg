package com.sickworm.intellij.jugg.mock

import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.SqlApkFileDatabase
import com.android.tools.deployer.tasks.TaskRunner
import com.android.tools.idea.run.DeploymentService
import com.intellij.debugger.DebuggerManager
import com.intellij.debugger.impl.DebuggerManagerImpl
import com.intellij.execution.RunManager
import com.intellij.mock.MockProject
import com.intellij.mock.MockRunManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.NotNullLazyValue
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Paths

class JuggMockProject(private val basePath: File): MockProject(null, {}) {

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
            else -> super.getService(serviceClass)
        }
    }

    override fun getBasePath(): String {
        return basePath.absolutePath
    }
}