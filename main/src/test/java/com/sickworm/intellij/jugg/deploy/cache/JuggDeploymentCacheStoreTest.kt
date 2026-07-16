package com.sickworm.intellij.jugg.deploy.cache

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.lang.Runnable
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JuggDeploymentCacheStoreTest {

    @Test
    fun `cache is isolated by project and persisted with atomic replacement`() {
        val firstPathManager = JuggPathManager(Files.createTempDirectory("jugg-cache-first").toFile())
        val secondPathManager = JuggPathManager(Files.createTempDirectory("jugg-cache-second").toFile())
        val entry = cacheEntry("first.apk")

        createStore(firstPathManager).store("device", "package", entry)

        assertEquals(entry, createStore(firstPathManager).load("device", "package"))
        assertNull(createStore(secondPathManager).load("device", "package"))
        assertTrue(firstPathManager.deploymentCacheDbFile.exists())
        assertFalse(firstPathManager.deploymentCacheTempFile.exists())
    }

    @Test
    fun `corrupted cache degrades to cache miss`() {
        val pathManager = JuggPathManager(Files.createTempDirectory("jugg-cache-corrupt").toFile())
        pathManager.deploymentCacheDbFile.parentFile.mkdirs()
        pathManager.deploymentCacheDbFile.writeText("broken")

        assertNull(createStore(pathManager).load("device", "package"))
    }

    @Test
    fun `two runtime stores preserve each others project entries`() {
        val pathManager = JuggPathManager(Files.createTempDirectory("jugg-cache-shared").toFile())
        val first = createStore(pathManager)
        val second = createStore(pathManager)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)

        Thread {
            start.await(5, TimeUnit.SECONDS)
            first.store("first-device", "package", cacheEntry("first.apk"))
            finished.countDown()
        }.start()
        Thread {
            start.await(5, TimeUnit.SECONDS)
            second.store("second-device", "package", cacheEntry("second.apk"))
            finished.countDown()
        }.start()
        start.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        val restored = createStore(pathManager)
        assertEquals(cacheEntry("first.apk"), restored.load("first-device", "package"))
        assertEquals(cacheEntry("second.apk"), restored.load("second-device", "package"))
    }

    private fun createStore(pathManager: JuggPathManager): JuggDeploymentCacheStore {
        return JuggDeploymentCacheStore(
            cacheDbFile = pathManager.deploymentCacheDbFile,
            taskRunnerManager = createTaskRunnerManager(pathManager),
        )
    }

    private fun createTaskRunnerManager(pathManager: JuggPathManager): TaskRunnerManager {
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
            runtimeType = "idea",
            runtimeVersion = "test",
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun cacheEntry(apkPath: String): JuggDeploymentCacheStore.CacheEntry {
        return JuggDeploymentCacheStore.CacheEntry(
            apkPaths = listOf(apkPath),
            overlayId = JuggDeploymentCacheStore.OverlayId(
                sha = "sha",
                isBaseInstall = false,
                overlayFiles = listOf(JuggDeploymentCacheStore.OverlayFile("classes.dex", 42L)),
            ),
        )
    }
}
