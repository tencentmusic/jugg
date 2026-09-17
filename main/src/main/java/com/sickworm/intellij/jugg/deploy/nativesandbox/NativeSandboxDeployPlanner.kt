package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.AppSandboxExecutor
import com.sickworm.intellij.jugg.deploy.run.DeployItem

/**
 * Decides whether NativeLib files can skip APK resign/reinstall for this deploy round.
 */
object NativeSandboxDeployPlanner {

    const val MIN_API = 26

    private val ABIS_64 = listOf("arm64-v8a", "x86_64")
    private val ABIS_32 = listOf("armeabi-v7a", "armeabi", "x86")
    private val SO_PATH = Regex("^lib/(arm64-v8a|armeabi-v7a|armeabi|x86_64|x86)/lib[^/]+\\.so$")

    enum class Arch {
        BIT_32,
        BIT_64,
    }

    fun plan(
        updateApkFiles: List<DeployItem>,
        arch: Arch,
        api: Int,
        sandboxMode: AppSandboxExecutor.Mode,
    ): NativeSandboxPlan {
        val nativeFiles = updateApkFiles.filter { it.type == CompileOutput.Type.NativeLib }
        val otherApkFiles = updateApkFiles.filter { it.type != CompileOutput.Type.NativeLib }
        if (nativeFiles.isEmpty()) {
            return NativeSandboxPlan.Skip("no native libraries")
        }
        if (otherApkFiles.isNotEmpty()) {
            return NativeSandboxPlan.Skip("apk update required for ${otherApkFiles.map { it.name }}")
        }
        if (api < MIN_API) {
            return NativeSandboxPlan.Skip("api $api < $MIN_API")
        }
        if (sandboxMode == AppSandboxExecutor.Mode.UNAVAILABLE) {
            return NativeSandboxPlan.Skip("app sandbox unavailable")
        }
        val abiDirs = groupByTargetAbi(nativeFiles, arch)
        if (abiDirs.isEmpty()) {
            return NativeSandboxPlan.Skip("no native libraries for target abi")
        }
        return NativeSandboxPlan.Attempt(
            nativeFiles = abiDirs.values.flatten(),
            otherApkFiles = emptyList(),
            abiDirs = abiDirs,
        )
    }

    fun parseAbi(path: String): String? {
        if (!SO_PATH.matches(path)) {
            return null
        }
        return path.substringAfter('/').substringBefore('/')
    }

    private fun groupByTargetAbi(
        nativeFiles: List<DeployItem>,
        arch: Arch,
    ): Map<String, List<DeployItem>> {
        val preferredAbis = if (arch == Arch.BIT_64) ABIS_64 else ABIS_32
        val grouped = linkedMapOf<String, MutableList<DeployItem>>()
        nativeFiles.forEach { item ->
            val abi = parseAbi(item.name) ?: return@forEach
            if (abi in preferredAbis) {
                grouped.getOrPut(abi) { mutableListOf() }.add(item)
            }
        }
        return grouped
    }
}

sealed class NativeSandboxPlan {
    data class Skip(val reason: String) : NativeSandboxPlan()
    data class Attempt(
        val nativeFiles: List<DeployItem>,
        val otherApkFiles: List<DeployItem>,
        val abiDirs: Map<String, List<DeployItem>>,
    ) : NativeSandboxPlan()
}
