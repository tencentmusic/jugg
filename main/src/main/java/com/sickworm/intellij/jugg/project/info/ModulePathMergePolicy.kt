package com.sickworm.intellij.jugg.project.info

import com.sickworm.intellij.jugg.compiler.BuildTarget

/**
 * Central policy for module identity and IDE/Gradle merge decisions that rely on
 * [ModuleInfo.moduleRootDir], especially when main and androidTest modules share one directory.
 */
object ModulePathMergePolicy {

    enum class ModuleSourceKind {
        Main,
        AndroidTest,
        JvmTest,
    }

    fun classifyByName(moduleName: String): ModuleSourceKind = when {
        moduleName.endsWith(".androidTest") -> ModuleSourceKind.AndroidTest
        moduleName.endsWith(".test") || moduleName.endsWith(".unitTest") -> ModuleSourceKind.JvmTest
        else -> ModuleSourceKind.Main
    }

    fun classify(module: ModuleInfo): ModuleSourceKind = when {
        module.isAndroidTestModule || module.name.endsWith(".androidTest") -> ModuleSourceKind.AndroidTest
        module.name.endsWith(".test") || module.name.endsWith(".unitTest") -> ModuleSourceKind.JvmTest
        else -> ModuleSourceKind.Main
    }

    fun isAndroidTestModule(module: ModuleInfo): Boolean =
        classify(module) == ModuleSourceKind.AndroidTest

    /**
     * Whether an IDE module should be collected into project info for the current [buildTarget].
     */
    fun shouldSkipIdeModule(stdModuleName: String, buildTarget: BuildTarget): Boolean {
        return when (classifyByName(stdModuleName)) {
            ModuleSourceKind.JvmTest -> true
            ModuleSourceKind.AndroidTest -> !buildTarget.includeAndroidTestSourceSet
            ModuleSourceKind.Main -> false
        }
    }

    fun selectIdeBuildVariant(stdModuleName: String, ownerBuildVariant: String): String {
        if (classifyByName(stdModuleName) != ModuleSourceKind.AndroidTest) {
            return ownerBuildVariant
        }
        return if (ownerBuildVariant.endsWith("AndroidTest")) {
            ownerBuildVariant
        } else {
            "${ownerBuildVariant}AndroidTest"
        }
    }

    /**
     * Resolves the IDE module name that corresponds to [gradleModule].
     *
     * Path-only matching is intentionally avoided after the exact name match: main and androidTest
     * modules share [ModuleInfo.moduleRootDir] and must stay distinct.
     */
    fun resolveIdeModuleName(gradleModule: ModuleInfo, ideModules: Collection<ModuleInfo>): String? {
        val moduleRootPath = gradleModule.moduleRootDir.absolutePath
        val gradleKind = classify(gradleModule)

        ideModules.firstOrNull {
            it.name == gradleModule.name && it.moduleRootDir.absolutePath == moduleRootPath
        }?.let { return it.name }

        return ideModules.firstOrNull {
            it.moduleRootDir.absolutePath == moduleRootPath && classify(it) == gradleKind
        }?.name
    }

    /**
     * Returns whether Gradle module [gradleModuleName] should be renamed to [ideModuleName]
     * during Gradle snapshot normalization.
     */
    fun shouldAlignGradleModuleName(
        gradleModule: ModuleInfo,
        gradleModuleName: String,
        ideModuleName: String,
    ): Boolean {
        if (gradleModuleName == ideModuleName) {
            return false
        }
        val gradleKind = classify(gradleModule)
        val ideKind = classifyByName(ideModuleName)
        return gradleKind == ideKind
    }

    /**
     * Whether a Gradle-only module should be appended when IDE project info has no entry for it.
     */
    fun shouldIncludeGradleOnlyModule(gradleModule: ModuleInfo, buildTarget: BuildTarget): Boolean {
        return when (classify(gradleModule)) {
            ModuleSourceKind.JvmTest -> false
            ModuleSourceKind.AndroidTest -> buildTarget.includeAndroidTestSourceSet
            ModuleSourceKind.Main -> true
        }
    }

    fun shouldIncludeIdeOnlyModule(ideModule: ModuleInfo, buildTarget: BuildTarget): Boolean {
        return when (classify(ideModule)) {
            ModuleSourceKind.JvmTest -> false
            ModuleSourceKind.AndroidTest -> buildTarget.includeAndroidTestSourceSet
            ModuleSourceKind.Main -> true
        }
    }

    fun shouldIncludeIdeAndroidTestCandidate(
        applicationId: String?,
        instrumentationTargetPackage: String?,
        hasSourceFiles: Boolean,
    ): Boolean {
        return getIdeAndroidTestCandidateFilterReason(
            applicationId,
            instrumentationTargetPackage,
            hasSourceFiles,
        ) == null
    }

    fun getIdeAndroidTestCandidateFilterReason(
        applicationId: String?,
        instrumentationTargetPackage: String?,
        hasSourceFiles: Boolean,
    ): String? {
        if (!hasValidAndroidTestMetadata(applicationId, instrumentationTargetPackage)) {
            return "missingMetadata"
        }
        return if (hasSourceFiles) null else "noSourceFiles"
    }

    fun hasValidAndroidTestMetadata(applicationId: String?, instrumentationTargetPackage: String?): Boolean {
        return isValidAndroidTestPackage(applicationId) &&
                isValidAndroidTestPackage(instrumentationTargetPackage)
    }

    private fun isValidAndroidTestPackage(value: String?): Boolean {
        return !value.isNullOrBlank() && value != UNINITIALIZED_APPLICATION_ID
    }

    /**
     * Picks the build variant when merging IDE and Gradle snapshots for the same module name.
     */
    fun selectMergedBuildVariant(ideModule: ModuleInfo, gradleModule: ModuleInfo): String {
        if (ideModule.buildVariant == gradleModule.buildVariant) {
            return ideModule.buildVariant
        }
        if (classify(ideModule) != classify(gradleModule)) {
            return ideModule.buildVariant
        }
        if (classify(ideModule) == ModuleSourceKind.Main && isAndroidTestModule(gradleModule)) {
            return ideModule.buildVariant
        }
        return gradleModule.buildVariant
    }

    private const val UNINITIALIZED_APPLICATION_ID = "uninitialized.application.id"
}
