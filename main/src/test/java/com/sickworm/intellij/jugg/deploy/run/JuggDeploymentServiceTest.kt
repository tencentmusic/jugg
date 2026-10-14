package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ILogger
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import java.io.File
import java.io.FileNotFoundException
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files

class JuggDeploymentServiceTest {

    @Test
    fun `memory cache reloads disk snapshot after another runtime writes`() {
        val pathManager = JuggPathManager(Files.createTempDirectory("jugg-service-cache").toFile())
        val first = createService(pathManager, "idea")
        val second = createService(pathManager, "standalone")
        val logger = mock<ILogger>()

        first.service.storeEntry("device", "package", listOf(apk(File(pathManager.projectDir, "first.apk").apply { createNewFile() }.path)), overlay("first"), first.executor, logger)
        assertEquals("first", first.service.loadEntry("device", "package", first.executor, logger)?.overlayId?.sha)

        second.service.storeEntry("device", "package", listOf(apk(File(pathManager.projectDir, "second.apk").apply { createNewFile() }.path)), overlay("second"), second.executor, logger)

        assertEquals("second", first.service.loadEntry("device", "package", first.executor, logger)?.overlayId?.sha)
    }

    @Test
    fun `memory cache rebuilds runtime objects for the active executor`() {
        val pathManager = JuggPathManager(Files.createTempDirectory("jugg-service-owner").toFile())
        val first = createService(pathManager, "idea")
        val secondExecutor = createExecutor("second")
        val logger = mock<ILogger>()

        first.service.storeEntry("device", "package", listOf(apk(File(pathManager.projectDir, "base.apk").apply { createNewFile() }.path)), overlay("stored"), first.executor, logger)

        val restored = first.service.loadEntry("device", "package", secondExecutor, logger)

        assertEquals("second", restored?.raw)
    }

    @Test
    fun `missing in-memory APK is rejected without invalidating another device`() {
        val pathManager = JuggPathManager(Files.createTempDirectory("jugg-service-stale").toFile())
        val fixture = createService(pathManager, "idea")
        val staleApk = File(pathManager.projectDir, "stale.apk").apply { createNewFile() }
        val currentApk = File(pathManager.projectDir, "current.apk").apply { createNewFile() }
        val logger = mock<ILogger>()
        fixture.service.storeEntry("old-device", "package", listOf(apk(staleApk.path)), overlay("old"), fixture.executor, logger)
        fixture.service.storeEntry("current-device", "package", listOf(apk(currentApk.path)), overlay("current"), fixture.executor, logger)
        fixture.service.loadEntry("old-device", "package", fixture.executor, logger)
        staleApk.delete()

        assertThrows(FileNotFoundException::class.java) {
            fixture.service.loadEntry("old-device", "package", fixture.executor, logger)
        }
        assertNull(fixture.service.loadCachedOverlayId("old-device", "package", mock<Logger>()))
        assertEquals("current", fixture.service.loadEntry("current-device", "package", fixture.executor, logger)?.overlayId?.sha)
    }

    private fun createService(pathManager: JuggPathManager, runtimeType: String): ServiceFixture {
        val executor = createExecutor(runtimeType)
        val store = JuggDeploymentCacheStore(pathManager.deploymentCacheDbFile,
            createTaskRunnerManager(pathManager, runtimeType))
        return ServiceFixture(JuggDeploymentService(pathManager, store, executor), executor)
    }

    private fun createExecutor(runtimeType: String): IApplyChangesExecutor {
        val executor = mock<IApplyChangesExecutor>()
        whenever(executor.parseApks(any())).doAnswer { invocation ->
            invocation.getArgument<List<String>>(0).map(::apk)
        }
        whenever(executor.createBaseOverlayId(any())).thenReturn(overlay("base", isBaseInstall = true))
        whenever(executor.buildOverlayId(any(), any())).doAnswer { invocation ->
            val files = invocation.getArgument<List<JuggOverlayFile>>(1)
            overlay(files.firstOrNull()?.path.orEmpty(), overlayFiles = files)
        }
        whenever(executor.createDeploymentCacheEntry(any(), any())).doAnswer { invocation ->
            val apks = invocation.getArgument<List<Apk>>(0)
            val overlayId = invocation.getArgument<JuggOverlayId>(1)
            JuggDeploymentCacheEntry(runtimeType, apks, overlayId)
        }
        return executor
    }

    private fun createTaskRunnerManager(pathManager: JuggPathManager, runtimeType: String): TaskRunnerManager {
        return TaskRunnerManager(
            logger = mock<Logger>(),
            deployStateManager = mock<IDeployStateManager>(),
            juggServer = mock<JuggServer>(),
            hostTaskExecutor = object : IHostTaskExecutor {
                override val isOnEdt: Boolean = false
                override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) = action.run()
            },
            pathManager = pathManager,
            runtimeType = runtimeType,
            runtimeVersion = "test",
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun apk(path: String): Apk = Apk(path, path, path, "package", emptyList(), emptyList(), emptyList(), emptyMap())

    private fun overlay(
        sha: String, isBaseInstall: Boolean = false,
        overlayFiles: List<JuggOverlayFile> = listOf(JuggOverlayFile(sha, 1L)),
    ): JuggOverlayId = JuggOverlayId(Any(), sha, isBaseInstall, overlayFiles)

    private data class ServiceFixture(
        val service: JuggDeploymentService,
        val executor: IApplyChangesExecutor,
    )
}
