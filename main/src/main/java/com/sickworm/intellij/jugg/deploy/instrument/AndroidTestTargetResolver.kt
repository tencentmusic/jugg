package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Resolves a source-file anchored androidTest run to the owning ModuleInfo and test APK.
 */
object AndroidTestTargetResolver {

    fun resolve(
        sourcePath: String,
        projectDir: File,
        modules: Collection<ModuleInfo>,
        apks: List<ApkInfo>,
    ): AndroidTestTarget {
        val module = resolveModule(sourcePath, projectDir, modules)
        val testApk = resolveTestApk(module, apks)
            ?: throw AndroidTestTargetResolveException(
                buildString {
                    appendLine("unable to resolve test APK for androidTest module.")
                    appendLine("module: ${module.name}")
                    appendLine("applicationId: ${module.applicationId.orEmpty()}")
                }.trimEnd()
            )

        return AndroidTestTarget(module, testApk)
    }

    fun resolveModule(
        sourcePath: String,
        projectDir: File,
        modules: Collection<ModuleInfo>,
    ): ModuleInfo {
        val sourceFile = File(sourcePath).let { if (it.isAbsolute) it else File(projectDir, sourcePath) }
            .canonicalFile
        if (!sourceFile.exists() || !sourceFile.isFile) {
            throw AndroidTestTargetResolveException(
                "sourcePath must be an existing file.\nsourcePath: $sourcePath"
            )
        }

        val matches = modules
            .filter { it.isAndroidTestModule }
            .filter { module -> module.sourceDirs.any { sourceFile.isUnder(it) } }

        val module = when (matches.size) {
            0 -> throw AndroidTestTargetResolveException(
                "sourcePath is not under any known androidTest source root.\nsourcePath: $sourcePath"
            )
            1 -> matches.first()
            else -> throw AndroidTestTargetResolveException(
                buildString {
                    appendLine("multiple androidTest modules match sourcePath.")
                    appendLine("sourcePath: $sourcePath")
                    appendLine("Candidates:")
                    matches.forEach { module ->
                        appendLine("- ${module.name}: ${module.sourceDirs.joinToString { it.path }}")
                    }
                }.trimEnd()
            )
        }
        return module
    }

    private fun resolveTestApk(module: ModuleInfo, apks: List<ApkInfo>): ApkInfo? {
        val byApplicationId = apks.filter { it.isTestApk && it.applicationId == module.applicationId }
        if (byApplicationId.size == 1) {
            return byApplicationId.first()
        }
        if (byApplicationId.size > 1) {
            throw ambiguousApk(module, byApplicationId)
        }

        val targetPackage = module.instrumentationTargetPackage
        val byTargetPackage = apks.filter {
            it.isTestApk &&
                !it.isOtherTargetingTestApk &&
                it.instrumentationTargetPackage == targetPackage
        }
        return when (byTargetPackage.size) {
            0 -> null
            1 -> byTargetPackage.first()
            else -> throw ambiguousApk(module, byTargetPackage)
        }
    }

    private fun ambiguousApk(module: ModuleInfo, apks: List<ApkInfo>): AndroidTestTargetResolveException {
        return AndroidTestTargetResolveException(
            buildString {
                appendLine("multiple test APKs match androidTest module.")
                appendLine("module: ${module.name}")
                appendLine("Candidates:")
                apks.forEach { apk ->
                    appendLine("- ${apk.applicationId}: ${apk.files.joinToString { it.apkFile.path }}")
                }
            }.trimEnd()
        )
    }

    private fun File.isUnder(root: File): Boolean {
        val sourcePath = canonicalFile.toPath()
        val rootPath = root.canonicalFile.toPath()
        return sourcePath.startsWith(rootPath)
    }
}

/** Resolved androidTest source owner and the instrumentation APK to launch. */
data class AndroidTestTarget(
    val module: ModuleInfo,
    val testApk: ApkInfo,
)

/** Raised when a sourcePath cannot be resolved to one deterministic androidTest target. */
class AndroidTestTargetResolveException(message: String) : RuntimeException(message)
