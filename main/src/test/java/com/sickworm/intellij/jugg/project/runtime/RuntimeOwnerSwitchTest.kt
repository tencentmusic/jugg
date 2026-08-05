package com.sickworm.intellij.jugg.project.runtime

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeOwnerSwitchTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `project write persists owner and reports runtime switch`() {
        val pathManager = JuggPathManager(temporaryFolder.newFolder("project"))
        val ideaManager = createManager(pathManager, "idea", "4.0")
        val standaloneManager = createManager(pathManager, "standalone", "4.0")

        ideaManager.runProjectWriteLocked("initialize idea") {}
        assertNull(ideaManager.consumeRuntimeOwnerChange())

        standaloneManager.runProjectWriteLocked("initialize standalone") {}

        val change = standaloneManager.consumeRuntimeOwnerChange()
        assertEquals("idea", change?.previousOwner?.runtimeType)
        assertEquals("standalone", change?.currentOwner?.runtimeType)
        assertEquals("standalone", RuntimeOwnerStore(pathManager.runtimeOwnerFile).read()?.runtimeType)
    }

    @Test
    fun `project write replaces corrupt runtime owner metadata`() {
        val pathManager = JuggPathManager(temporaryFolder.newFolder("project"))
        pathManager.runtimeOwnerFile.parentFile.mkdirs()
        pathManager.runtimeOwnerFile.writeText("{")
        val manager = createManager(pathManager, "standalone", "4.0")

        manager.runProjectWriteLocked("initialize standalone") {}

        assertEquals("standalone", RuntimeOwnerStore(pathManager.runtimeOwnerFile).read()?.runtimeType)
        assertNull(manager.consumeRuntimeOwnerChange())
    }

    private fun createManager(pathManager: JuggPathManager, runtimeType: String, runtimeVersion: String): TaskRunnerManager {
        return TaskRunnerManager(
            logger = mock<Logger>(),
            deployStateManager = mock<IDeployStateManager>(),
            juggServer = mock<JuggServer>(),
            hostTaskExecutor = object : IHostTaskExecutor {
                override val isOnEdt: Boolean = false

                override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
                    action.run()
                }
            },
            pathManager = pathManager,
            runtimeType = runtimeType,
            runtimeVersion = runtimeVersion,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
