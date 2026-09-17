package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSandboxDeployPlannerTest {

    @Test
    fun `native-only run-as arm64 is attempted`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(nativeLib("lib/arm64-v8a/libdtmp.so")),
            arch = NativeSandboxDeployPlanner.Arch.BIT_64,
            api = 26,
            sandboxMode = AppSandboxExecutor.Mode.RUN_AS,
        )

        val attempt = plan as NativeSandboxPlan.Attempt
        assertEquals(listOf("lib/arm64-v8a/libdtmp.so"), attempt.nativeFiles.map { it.name })
        assertEquals(setOf("arm64-v8a"), attempt.abiDirs.keys)
        assertTrue(attempt.otherApkFiles.isEmpty())
    }

    @Test
    fun `manifest plus native lib skips sandbox deploy`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(
                deployItem("AndroidManifest.xml", CompileOutput.Type.Res),
                nativeLib("lib/arm64-v8a/libdtmp.so"),
            ),
            arch = NativeSandboxDeployPlanner.Arch.BIT_64,
            api = 30,
            sandboxMode = AppSandboxExecutor.Mode.RUN_AS,
        )

        val skip = plan as NativeSandboxPlan.Skip
        assertTrue(skip.reason.contains("AndroidManifest.xml"))
    }

    @Test
    fun `unavailable sandbox skips sandbox deploy`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(nativeLib("lib/arm64-v8a/libdtmp.so")),
            arch = NativeSandboxDeployPlanner.Arch.BIT_64,
            api = 30,
            sandboxMode = AppSandboxExecutor.Mode.UNAVAILABLE,
        )

        assertTrue(plan is NativeSandboxPlan.Skip)
    }

    @Test
    fun `api below 26 skips sandbox deploy`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(nativeLib("lib/arm64-v8a/libdtmp.so")),
            arch = NativeSandboxDeployPlanner.Arch.BIT_64,
            api = 25,
            sandboxMode = AppSandboxExecutor.Mode.RUN_AS,
        )

        val skip = plan as NativeSandboxPlan.Skip
        assertTrue(skip.reason.contains("api 25"))
    }

    @Test
    fun `wrong abi skips sandbox deploy`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(nativeLib("lib/armeabi-v7a/libdtmp.so")),
            arch = NativeSandboxDeployPlanner.Arch.BIT_64,
            api = 30,
            sandboxMode = AppSandboxExecutor.Mode.RUN_AS,
        )

        val skip = plan as NativeSandboxPlan.Skip
        assertTrue(skip.reason.contains("no native libraries for target abi"))
    }

    @Test
    fun `unsafe native path is ignored for sandbox deploy`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(nativeLib("libcustom.so")),
            arch = NativeSandboxDeployPlanner.Arch.BIT_64,
            api = 30,
            sandboxMode = AppSandboxExecutor.Mode.RUN_AS,
        )

        assertTrue(plan is NativeSandboxPlan.Skip)
    }

    @Test
    fun `direct shell 32-bit selects armeabi-v7a`() {
        val plan = NativeSandboxDeployPlanner.plan(
            updateApkFiles = listOf(
                nativeLib("lib/armeabi-v7a/libfoo.so"),
                nativeLib("lib/arm64-v8a/libfoo.so"),
            ),
            arch = NativeSandboxDeployPlanner.Arch.BIT_32,
            api = 28,
            sandboxMode = AppSandboxExecutor.Mode.DIRECT_SHELL,
        )

        val attempt = plan as NativeSandboxPlan.Attempt
        assertEquals(listOf("lib/armeabi-v7a/libfoo.so"), attempt.nativeFiles.map { it.name })
    }

    private fun nativeLib(name: String): DeployItem {
        return deployItem(name, CompileOutput.Type.NativeLib)
    }

    private fun deployItem(name: String, type: CompileOutput.Type): DeployItem {
        return DeployItem(
            name = name,
            type = type,
            checksum = 1L,
            content = byteArrayOf(1),
            apkPath = "/tmp/app.apk",
            targetApkPaths = listOf("/tmp/app.apk"),
        )
    }
}
