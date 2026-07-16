package com.sickworm.intellij.jugg.project.info

import com.sickworm.intellij.jugg.compiler.BuildTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModulePathMergePolicyTest {

    private val projectDir = File("/project")
    private val moduleDir = File("/project/common/download")

    private fun module(
        name: String,
        buildVariant: String,
        instrumentationTargetPackage: String? = null,
    ) = ModuleInfo.virtualModule.copy(
        name = name,
        moduleRootDir = moduleDir,
        projectRootDir = projectDir,
        buildVariant = buildVariant,
        buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, buildVariant, buildDirRelativePath = ""),
        instrumentationTargetPackage = instrumentationTargetPackage,
    )

    @Test
    fun `shouldSkipIdeModule skips androidTest when buildTarget is APP`() {
        assertTrue(ModulePathMergePolicy.shouldSkipIdeModule("common.download.androidTest", BuildTarget.APP))
    }

    @Test
    fun `shouldSkipIdeModule keeps androidTest when buildTarget is ANDROID_TEST`() {
        assertFalse(ModulePathMergePolicy.shouldSkipIdeModule("common.download.androidTest", BuildTarget.ANDROID_TEST))
    }

    @Test
    fun `resolveIdeModuleName does not map androidTest gradle module to main ide module by path`() {
        val mainIde = module("common.download", "debug")
        val gradleAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )

        assertNull(ModulePathMergePolicy.resolveIdeModuleName(gradleAndroidTest, listOf(mainIde)))
    }

    @Test
    fun `resolveIdeModuleName maps androidTest gradle module to androidTest ide module by path and kind`() {
        val androidTestIde = module("common.download.androidTest", "debugAndroidTest")
        val gradleAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )

        assertEquals(
            "common.download.androidTest",
            ModulePathMergePolicy.resolveIdeModuleName(gradleAndroidTest, listOf(androidTestIde)),
        )
    }

    @Test
    fun `shouldAlignGradleModuleName blocks cross kind rename`() {
        val gradleAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )
        assertFalse(
            ModulePathMergePolicy.shouldAlignGradleModuleName(
                gradleAndroidTest,
                "common.download.androidTest",
                "common.download",
            )
        )
    }

    @Test
    fun `shouldIncludeGradleOnlyModule keeps androidTest when buildTarget is ANDROID_TEST`() {
        val gradleAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )
        assertTrue(
            ModulePathMergePolicy.shouldIncludeGradleOnlyModule(gradleAndroidTest, BuildTarget.ANDROID_TEST)
        )
    }

    @Test
    fun `shouldIncludeGradleOnlyModule skips androidTest when buildTarget is APP`() {
        val gradleAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )
        assertFalse(
            ModulePathMergePolicy.shouldIncludeGradleOnlyModule(gradleAndroidTest, BuildTarget.APP)
        )
    }

    @Test
    fun `shouldIncludeIdeOnlyModule keeps androidTest when buildTarget is ANDROID_TEST`() {
        val ideAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )
        assertTrue(
            ModulePathMergePolicy.shouldIncludeIdeOnlyModule(ideAndroidTest, BuildTarget.ANDROID_TEST)
        )
    }

    @Test
    fun `shouldIncludeIdeOnlyModule skips androidTest when buildTarget is APP`() {
        val ideAndroidTest = module(
            name = "common.download.androidTest",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )
        assertFalse(
            ModulePathMergePolicy.shouldIncludeIdeOnlyModule(ideAndroidTest, BuildTarget.APP)
        )
    }

    @Test
    fun `shouldIncludeIdeAndroidTestCandidate rejects uninitialized target package`() {
        assertFalse(
            ModulePathMergePolicy.shouldIncludeIdeAndroidTestCandidate(
                applicationId = "com.example.test",
                instrumentationTargetPackage = "uninitialized.application.id",
                hasSourceFiles = true,
            )
        )
    }

    @Test
    fun `shouldIncludeIdeAndroidTestCandidate does not require known gradle androidTest module`() {
        assertTrue(
            ModulePathMergePolicy.shouldIncludeIdeAndroidTestCandidate(
                applicationId = "com.example.test",
                instrumentationTargetPackage = "com.example.test",
                hasSourceFiles = true,
            )
        )
        assertTrue(
            ModulePathMergePolicy.shouldIncludeIdeAndroidTestCandidate(
                applicationId = "com.example.fake.test",
                instrumentationTargetPackage = "com.example.fake.test",
                hasSourceFiles = true,
            )
        )
    }

    @Test
    fun `shouldIncludeIdeAndroidTestCandidate requires source files`() {
        assertTrue(
            ModulePathMergePolicy.shouldIncludeIdeAndroidTestCandidate(
                applicationId = "com.example.test",
                instrumentationTargetPackage = "com.example.test",
                hasSourceFiles = true,
            )
        )
        assertFalse(
            ModulePathMergePolicy.shouldIncludeIdeAndroidTestCandidate(
                applicationId = "com.example.test",
                instrumentationTargetPackage = "com.example.test",
                hasSourceFiles = false,
            )
        )
    }

    @Test
    fun `getIdeAndroidTestCandidateFilterReason explains rejected androidTest candidates`() {
        assertEquals(
            "missingMetadata",
            ModulePathMergePolicy.getIdeAndroidTestCandidateFilterReason(
                applicationId = "com.example.test",
                instrumentationTargetPackage = null,
                hasSourceFiles = true,
            ),
        )
        assertNull(
            ModulePathMergePolicy.getIdeAndroidTestCandidateFilterReason(
                applicationId = "com.example.fake.test",
                instrumentationTargetPackage = "com.example.fake.test",
                hasSourceFiles = true,
            ),
        )
        assertEquals(
            "noSourceFiles",
            ModulePathMergePolicy.getIdeAndroidTestCandidateFilterReason(
                applicationId = "com.example.test",
                instrumentationTargetPackage = "com.example.test",
                hasSourceFiles = false,
            ),
        )
        assertEquals(
            "noSourceFiles",
            ModulePathMergePolicy.getIdeAndroidTestCandidateFilterReason(
                applicationId = "com.example.test",
                instrumentationTargetPackage = "com.example.test",
                hasSourceFiles = false,
            ),
        )
    }

    @Test
    fun `selectMergedBuildVariant keeps main module debug when gradle snapshot is androidTest`() {
        val mainIde = module("common.download", "debug")
        val androidTestGradle = module(
            name = "common.download",
            buildVariant = "debugAndroidTest",
            instrumentationTargetPackage = "com.example.test",
        )
        assertEquals("debug", ModulePathMergePolicy.selectMergedBuildVariant(mainIde, androidTestGradle))
    }

    @Test
    fun `selectIdeBuildVariant converts androidTest IDE module to test variant`() {
        assertEquals(
            "debugAndroidTest",
            ModulePathMergePolicy.selectIdeBuildVariant("common.download.androidTest", "debug"),
        )
    }

    @Test
    fun `selectIdeBuildVariant keeps flavor name when converting androidTest IDE module`() {
        assertEquals(
            "jooxDebugAndroidTest",
            ModulePathMergePolicy.selectIdeBuildVariant("wemusic.androidTest", "jooxDebug"),
        )
    }

    @Test
    fun `selectIdeBuildVariant keeps existing androidTest variant`() {
        assertEquals(
            "debugAndroidTest",
            ModulePathMergePolicy.selectIdeBuildVariant("app.androidTest", "debugAndroidTest"),
        )
    }

    @Test
    fun `findIncludedBuildModuleRoots uses snapshot origin and keeps primary modules excluded`() {
        val externalPrimary = module("externalPrimary", "debug").copy(
            moduleRootDir = File("/external/primary"),
        )
        val included = module("included", "debug").copy(
            moduleRootDir = File("/composite/included"),
        )
        val duplicatedInIncluded = externalPrimary.copy(name = "duplicated")

        val roots = ModulePathMergePolicy.findIncludedBuildModuleRoots(
            listOf(
                JuggProjectInfo(mapOf(externalPrimary.name to externalPrimary), agpR8Classpath = null),
                JuggProjectInfo(
                    mapOf(included.name to included, duplicatedInIncluded.name to duplicatedInIncluded),
                    agpR8Classpath = null,
                ),
            )
        )

        assertEquals(setOf(included.moduleRootDir.absoluteFile.normalize()), roots)
    }

    @Test
    fun `findIncludedBuildModuleRoots returns empty when primary snapshot is missing`() {
        val included = module("included", "debug").copy(moduleRootDir = File("/composite/included"))

        val roots = ModulePathMergePolicy.findIncludedBuildModuleRoots(
            listOf(null, JuggProjectInfo(mapOf(included.name to included), agpR8Classpath = null))
        )

        assertTrue(roots.isEmpty())
    }
}
