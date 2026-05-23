package com.sickworm.intellij.jugg.deploy.direct

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.CachedOverlayId
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class DirectOverlayStateCheckerTest {

    @Test
    fun `checkRecover should return mismatch when deploy history overlay id is missing`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID overlay-id"),
            historyOverlayIds = emptyMap(),
            cachedOverlayId = CachedOverlayId(sha = "overlay-id", isBaseInstall = false),
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkRecover("device-1", "com.example.app"))
    }

    @Test
    fun `checkRecover should mismatch when local deployment cache is missing`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID overlay-id"),
            historyOverlayIds = mapOf("com.example.app" to "overlay-id"),
            cachedOverlayId = null,
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkRecover("device-1", "com.example.app"))
    }

    @Test
    fun `checkRecover should mismatch when local deployment cache disagrees with history`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID overlay-id"),
            historyOverlayIds = mapOf("com.example.app" to "history-overlay"),
            cachedOverlayId = CachedOverlayId(sha = "stale-overlay", isBaseInstall = false),
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkRecover("device-1", "com.example.app"))
    }

    @Test
    fun `checkRecover should trust cache for device check when local overlay mismatch is skipped`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ NO_DIR"),
            historyOverlayIds = mapOf("com.example.app" to "stale-history-id"),
            cachedOverlayId = CachedOverlayId(sha = "fresh-cache-id", isBaseInstall = true),
        )

        assertEquals(
            DirectOverlayStateCheckResult.MATCHED,
            checker.checkRecover("device-1", "com.example.app", isSkipExceptOverlayCheck = true),
        )
    }

    @Test
    fun `checkRecover should match when history cache and device overlay agree`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID overlay-id"),
            historyOverlayIds = mapOf("com.example.app" to "overlay-id"),
            cachedOverlayId = CachedOverlayId(sha = "overlay-id", isBaseInstall = false),
        )

        assertEquals(DirectOverlayStateCheckResult.MATCHED, checker.checkRecover("device-1", "com.example.app"))
    }

    @Test
    fun `checkRecover should mismatch when history and cache agree but device overlay differs`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID other-id"),
            historyOverlayIds = mapOf("com.example.app" to "overlay-id"),
            cachedOverlayId = CachedOverlayId(sha = "overlay-id", isBaseInstall = false),
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkRecover("device-1", "com.example.app"))
    }

    @Test
    fun `checkRecover should match base install when history cache and device agree`() {
        val checker = createRecoverChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ NO_DIR"),
            historyOverlayIds = mapOf("com.example.app" to "base-overlay"),
            cachedOverlayId = CachedOverlayId(sha = "base-overlay", isBaseInstall = true),
        )

        assertEquals(DirectOverlayStateCheckResult.MATCHED, checker.checkRecover("device-1", "com.example.app"))
    }

    @Test
    fun `checkDevice missing overlay dir matches base install`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ NO_DIR"),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.MATCHED, checker.checkDevice("com.example.app", ""))
    }

    @Test
    fun `checkDevice missing overlay dir mismatches non base install`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ NO_DIR"),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkDevice("com.example.app", "overlay-id"))
    }

    @Test
    fun `checkDevice empty overlay id matches base install`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID "),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.MATCHED, checker.checkDevice("com.example.app", ""))
    }

    @Test
    fun `checkDevice readable overlay id matches expected id`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID overlay-id"),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.MATCHED, checker.checkDevice("com.example.app", "overlay-id"))
    }

    @Test
    fun `checkDevice readable overlay id mismatches expected id`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ ID other-id"),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkDevice("com.example.app", "overlay-id"))
    }

    @Test
    fun `checkDevice overlay dir without readable id is mismatch`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("__JUGG_OVERLAY_STATE__ MISSING_ID"),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.MISMATCHED, checker.checkDevice("com.example.app", "overlay-id"))
    }

    @Test
    fun `checkDevice unexpected adb output is unknown`() {
        val checker = DirectOverlayStateChecker(
            adb = FakeAdb("run-as: package not debuggable"),
            logger = Mockito.mock(Logger::class.java),
        )

        assertEquals(DirectOverlayStateCheckResult.UNKNOWN, checker.checkDevice("com.example.app", "overlay-id"))
    }

    private fun createRecoverChecker(
        adb: IDeviceAdb,
        historyOverlayIds: Map<String, String>,
        cachedOverlayId: CachedOverlayId?,
    ): DirectOverlayStateChecker {
        val historyManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(historyManager.lastDeployOverlayIds).thenReturn(historyOverlayIds)
        val deploymentService = object : IJuggDeploymentService {
            override fun loadCachedOverlayId(deviceSerial: String, packageName: String, logger: Logger): CachedOverlayId? {
                return cachedOverlayId
            }
        }
        return DirectOverlayStateChecker(
            adb = adb,
            logger = Mockito.mock(Logger::class.java),
            deployHistoryManager = historyManager,
            deploymentService = deploymentService,
        )
    }

    private class FakeAdb(private val output: String) : IDeviceAdb {
        override val displayName: String = "fake"
        override val api: Int = 35
        override val serial: String = "serial"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = output
        override fun execAdbShellScript(cmd: String): String = output
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }
}
