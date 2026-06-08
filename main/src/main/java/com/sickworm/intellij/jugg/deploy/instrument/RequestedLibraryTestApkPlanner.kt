package com.sickworm.intellij.jugg.deploy.instrument

import com.sickworm.intellij.jugg.project.ModulePathMergePolicy
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Plans the library Test APK build requested by the current source-anchored AndroidTest run.
 */
object RequestedLibraryTestApkPlanner {

    fun plan(
        spec: AndroidTestRunSpec?,
        projectDir: File,
        modules: Collection<ModuleInfo>,
    ): LibraryTestApkBackfillPlan? {
        return resolveSelfTargetingLibraryModule(spec, projectDir, modules)?.let(LibraryTestApkBackfillPlanner::plan)
    }

    fun resolveSelfTargetingLibraryModule(
        spec: AndroidTestRunSpec?,
        projectDir: File,
        modules: Collection<ModuleInfo>,
    ): ModuleInfo? {
        val sourcePath = spec?.sourcePath?.takeIf { it.isNotBlank() } ?: return null
        val androidTestModule = resolveAndroidTestModule(sourcePath, projectDir, modules)
            ?: resolveOwnerLibraryModule(sourcePath, projectDir, modules)?.toAndroidTestModule()
            ?: return null
        if (androidTestModule.applicationId != androidTestModule.instrumentationTargetPackage) {
            return null
        }
        if (androidTestModule.moduleType != ModuleInfo.Type.Library) {
            return null
        }
        return androidTestModule
    }

    private fun resolveAndroidTestModule(
        sourcePath: String,
        projectDir: File,
        modules: Collection<ModuleInfo>,
    ): ModuleInfo? {
        return runCatching {
            AndroidTestTargetResolver.resolveModule(sourcePath, projectDir, modules)
        }.getOrNull()
    }

    private fun resolveOwnerLibraryModule(
        sourcePath: String,
        projectDir: File,
        modules: Collection<ModuleInfo>,
    ): ModuleInfo? {
        val sourceFile = File(sourcePath).let { if (it.isAbsolute) it else File(projectDir, sourcePath) }
            .canonicalFile
        return modules
            .filter { it.moduleType == ModuleInfo.Type.Library && !it.isAndroidTestModule }
            .singleOrNull { module ->
                val androidTestRoot = File(module.moduleRootDir, "src/androidTest")
                sourceFile.isUnder(androidTestRoot)
            }
    }

    private fun ModuleInfo.toAndroidTestModule(): ModuleInfo {
        val androidTestModuleName = ModulePathMergePolicy.androidTestModuleName(name)
        return copy(
            name = androidTestModuleName,
            buildVariant = ModulePathMergePolicy.selectIdeBuildVariant(androidTestModuleName, buildVariant),
            instrumentationTargetPackage = applicationId,
        )
    }

    private fun File.isUnder(root: File): Boolean {
        val sourcePath = canonicalFile.toPath()
        val rootPath = root.canonicalFile.toPath()
        return sourcePath.startsWith(rootPath)
    }
}
