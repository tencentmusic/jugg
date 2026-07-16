package com.sickworm.intellij.jugg.ide.bean

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.LocalClasspathStoragePathManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.Variant
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class JuggGradleCompileOptionsTest {

    private fun makeOptions(
        projectDir: File,
        parentDir: File,
        buildTarget: BuildTarget = BuildTarget.APP,
    ): JuggGradleCompileOptions {
        val pathManager = JuggPathManager(projectDir)
        return JuggGradleCompileOptions(
            projectRootPath = projectDir.absolutePath,
            localClasspathStoragePath = LocalClasspathStoragePathManager(File(projectDir, "build/jugg/classpath")),
            initGradleFilePath = pathManager.initGradleFilePath.absolutePath,
            compileCommand = "./gradlew clean assembleDebug",
            outputApkName = "app-debug.apk",
            isRemoteCompile = true,
            isSyncAllProjects = false,
            remoteSshUser = "tester",
            remoteSshPassword = "",
            remoteSshIp = "127.0.0.1",
            remoteSshPort = 22,
            localToRemoteIftConfigName = "local_config",
            localToRemoteSyncPath = parentDir.absolutePath,
            remoteSyncPath = "/remote",
            remoteToLocalIftConfigName = "remote_config",
            remoteToLocalSyncPath = File(parentDir, "fetch").absolutePath,
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.RSYNC_SIMPLE,
            environmentVariables = "",
            buildTarget = buildTarget,
        )
    }

    @Test
    fun remoteInitGradleFilePath_shouldKeepDotGradleRootRelativePath() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_parent").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir)

            assertEquals(
                File(options.remoteProjectPath, ".gradle/jugg/readProjectInfo.gradle.kts").path,
                options.remoteInitGradleFilePath,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun buildTarget_defaultsToApp() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_bt").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir)
            assertEquals(BuildTarget.APP, options.buildTarget)
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun buildTarget_canBeSetToAndroidTest() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_bt2").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST)
            assertEquals(BuildTarget.ANDROID_TEST, options.buildTarget)
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun copy_keepsLibraryTestApkHistoryOptions() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_history").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir).copy(
                libraryTestApkGradleTasks = listOf(":library1:assembleDebugAndroidTest"),
                libraryTestApkOutputPatterns = listOf("library1/build/outputs/apk/androidTest/debug/*.apk"),
            )

            val copy = options.copy(compileCommand = "./gradlew :app:assembleDebug")

            assertEquals(listOf(":library1:assembleDebugAndroidTest"), copy.libraryTestApkGradleTasks)
            assertEquals(
                listOf("library1/build/outputs/apk/androidTest/debug/*.apk"),
                copy.libraryTestApkOutputPatterns,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun withGradleCacheRefresh_shouldAppendCacheBypassArgumentsOnce() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_refresh").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir).copy(
                compileCommand = "./gradlew :app:assembleDebug",
            )

            val refreshed = options.withGradleCacheRefresh().withGradleCacheRefresh()

            assertEquals(
                "./gradlew :app:assembleDebug --no-build-cache --rerun-tasks",
                refreshed.compileCommand,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun parseRemoteSyncExcludePatterns_shouldIgnoreBlankLinesAndComments() {
        val patterns = parseRemoteSyncExcludePatterns(
            """
            # generated files
            app/src/debug/mock/**

            local-temp/
            **/*.keystore
            """.trimIndent(),
        )

        assertEquals(listOf("app/src/debug/mock/**", "local-temp/", "**/*.keystore"), patterns)
    }

    @Test
    fun parseRemoteSyncExcludePatterns_shouldAcceptSemicolonSeparatedInput() {
        val patterns = parseRemoteSyncExcludePatterns(
            "app/src/debug/mock/**; local-temp/; **/*.keystore",
        )

        assertEquals(listOf("app/src/debug/mock/**", "local-temp/", "**/*.keystore"), patterns)
    }

    @Test
    fun parseRemoteSyncExcludePatterns_shouldKeepCommaSeparatedInputCompatible() {
        val patterns = parseRemoteSyncExcludePatterns(
            "app/src/debug/mock/**, local-temp/, **/*.keystore",
        )

        assertEquals(listOf("app/src/debug/mock/**", "local-temp/", "**/*.keystore"), patterns)
    }

    @Test
    fun parseRemoteSyncExcludePatterns_shouldAllowRootPattern() {
        val patterns = parseRemoteSyncExcludePatterns("/.git/")

        assertEquals(listOf("/.git/"), patterns)
    }

    @Test
    fun parseRemoteSyncExcludePatterns_shouldRejectParentPaths() {
        assertFailsWith<JuggException> {
            parseRemoteSyncExcludePatterns("../shared/cache/**")
        }
    }

    @Test
    fun effectiveRemoteSyncExcludePatterns_shouldUseDefaultsWhenNotCustomized() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_default_excludes").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir).copy(
                remoteSyncExcludePatterns = listOf("legacy/**"),
            )

            assertEquals(
                listOf("local.properties", ".idea/", "*.iml", ".git/objects/", ".git/modules/", ".cxx/"),
                options.effectiveRemoteSyncExcludePatterns,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun effectiveRemoteSyncExcludePatterns_shouldNotDependOnTransferRoot() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_shared_root").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir).copy(
                syncMode = SyncMode.RSYNC,
                isSyncAllProjects = true,
            )

            assertEquals(
                makeOptions(projectDir, parentDir).effectiveRemoteSyncExcludePatterns,
                options.effectiveRemoteSyncExcludePatterns,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun effectiveRemoteSyncExcludePatterns_shouldUseCustomizedListIncludingEmpty() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_custom_excludes").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir).copy(
                remoteSyncExcludePatterns = listOf("local-temp/**"),
                isRemoteSyncExcludePatternsCustomized = true,
            )

            assertEquals(listOf("local-temp/**"), options.effectiveRemoteSyncExcludePatterns)
            assertEquals(
                emptyList(),
                options.copy(remoteSyncExcludePatterns = emptyList()).effectiveRemoteSyncExcludePatterns,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_usesRequestedAppTask() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST).copy(
                compileCommand = "./gradlew :app:assembleDebug",
            )
            val modules = modules(
                module("app", ModuleInfo.Type.Application, "debug", variants = listOf("debug", "developmentDebug")),
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
                module("library1.androidTest", buildVariant = "developmentDebugAndroidTest", isAndroidTest = true),
            )

            assertEquals("debugAndroidTest", inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_supportsCamelCaseVariantTask() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer_camel").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST).copy(
                compileCommand = "./gradlew :app:assembleDevelopmentDebug",
            )
            val modules = modules(
                module("app", ModuleInfo.Type.Application, "debug", variants = listOf("debug", "developmentDebug")),
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
                module("library1.androidTest", buildVariant = "developmentDebugAndroidTest", isAndroidTest = true),
            )

            assertEquals("developmentDebugAndroidTest", inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_returnsNullForAmbiguousFallbackVariants() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer_ambiguous").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST).copy(
                compileCommand = "./gradlew assembleDebug",
            )
            val modules = modules(
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
                module("library1.androidTest", buildVariant = "developmentDebugAndroidTest", isAndroidTest = true),
            )

            assertNull(inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_supportsRootAssembleTask() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer_root").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST).copy(
                compileCommand = "./gradlew assembleDebug --console=plain -Pfoo=bar",
            )
            val modules = modules(
                module("app", ModuleInfo.Type.Application, "debug", variants = listOf("debug", "developmentDebug")),
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
                module("library1.androidTest", buildVariant = "developmentDebugAndroidTest", isAndroidTest = true),
            )

            assertEquals("debugAndroidTest", inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_supportsRootProcessManifestTask() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer_manifest").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST).copy(
                compileCommand = "./gradlew processDevelopmentDebugManifest",
            )
            val modules = modules(
                module("app", ModuleInfo.Type.Application, "debug", variants = listOf("debug", "developmentDebug")),
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
                module("library1.androidTest", buildVariant = "developmentDebugAndroidTest", isAndroidTest = true),
            )

            assertEquals("developmentDebugAndroidTest", inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_returnsNullForAmbiguousRootTask() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer_root_ambiguous").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST).copy(
                compileCommand = "./gradlew assembleDebug",
            )
            val modules = modules(
                module("app", ModuleInfo.Type.Application, "debug", variants = listOf("debug")),
                module("demo", ModuleInfo.Type.Application, "debug", variants = listOf("debug")),
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
            )

            assertNull(inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun inferLibraryTestApkHistoryBuildVariant_returnsNullForAppTarget() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_infer_app").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.APP).copy(
                compileCommand = "./gradlew :app:assembleDebug",
            )
            val modules = modules(
                module("app", ModuleInfo.Type.Application, "debug", variants = listOf("debug")),
                module("app.androidTest", buildVariant = "debugAndroidTest", isAndroidTest = true),
            )

            assertNull(inferLibraryTestApkHistoryBuildVariant(options, modules))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    private fun modules(vararg modules: ModuleInfo): Map<String, ModuleInfo> {
        return modules.associateBy { it.name }
    }

    private fun module(
        name: String,
        type: ModuleInfo.Type = ModuleInfo.Type.Library,
        buildVariant: String,
        variants: List<String> = emptyList(),
        isAndroidTest: Boolean = false,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = type,
            buildVariant = buildVariant,
            variants = variants.map { Variant(it, null) },
            instrumentationTargetPackage = if (isAndroidTest) "com.example.test" else null,
        )
    }
}
