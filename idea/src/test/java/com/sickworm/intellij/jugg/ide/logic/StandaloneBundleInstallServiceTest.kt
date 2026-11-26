package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.project.runtime.StandaloneHotUpdateManifest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneBundleInstallServiceTest {

    @Test
    fun `installer failure includes process output`() {
        val message = StandaloneBundleInstallService.installerFailureMessage(
            exitCode = 1,
            output = "Standalone runtime build-1 would downgrade the active runtime",
        )

        assertContains(message, "Standalone Bundle installer exited with 1")
        assertContains(message, "would downgrade the active runtime")
    }

    @Test
    fun `idea managed runtime updates when embedded tooling build changes`() {
        assertTrue(StandaloneBundleInstallService.shouldInstallRuntime(
            isCliInstalled = true,
            activeManifest = manifest("runtime-1", "tooling-1", "idea"),
            bundledReleaseBuildId = "tooling-2",
        ))
    }

    @Test
    fun `missing idea runtime is installed when cli already exists`() {
        assertTrue(StandaloneBundleInstallService.shouldInstallRuntime(
            isCliInstalled = true,
            activeManifest = null,
            bundledReleaseBuildId = "tooling-1",
        ))
    }

    @Test
    fun `runtime is not installed before cli setup`() {
        assertFalse(StandaloneBundleInstallService.shouldInstallRuntime(
            isCliInstalled = false,
            activeManifest = null,
            bundledReleaseBuildId = "tooling-1",
        ))
    }

    @Test
    fun `compatible hot update remains active for the same embedded tooling build`() {
        assertFalse(StandaloneBundleInstallService.shouldInstallRuntime(
            isCliInstalled = true,
            activeManifest = manifest("hot-update-2", "tooling-1", "idea"),
            bundledReleaseBuildId = "tooling-1",
        ))
    }

    @Test
    fun `external runtime is not replaced automatically`() {
        assertFalse(StandaloneBundleInstallService.shouldInstallRuntime(
            isCliInstalled = true,
            activeManifest = manifest("runtime-2", "tooling-2", "external"),
            bundledReleaseBuildId = "tooling-1",
        ))
    }

    private fun manifest(releaseBuildId: String, toolingReleaseBuildId: String, managedBy: String) =
        StandaloneHotUpdateManifest(
            schemaVersion = 1,
            runtimeApiVersion = 1,
            bootstrapApiVersion = 1,
            targetVersion = "4.0.0-SNAPSHOT",
            releaseBuildId = releaseBuildId,
            releaseChannel = "snapshot",
            toolingReleaseBuildId = toolingReleaseBuildId,
            managedBy = managedBy,
            jarFileNames = listOf("main.jar"),
            jarSha256 = mapOf("main.jar" to "sha256"),
        )
}
