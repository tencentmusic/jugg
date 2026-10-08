package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.OnContextUpdate
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.project.data.ComposeResourceDirectory
import com.sickworm.intellij.jugg.project.data.ComposeResourceInfo
import com.sickworm.intellij.jugg.project.data.ComposeResourceSupportStatus
import com.sickworm.intellij.jugg.project.data.ExternalBuildGeneratedLanguage
import com.sickworm.intellij.jugg.project.data.ExternalBuildGeneratedSourceDir
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputDir
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule.CppHeader
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule.CppSource
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule.Dart
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule.FlutterAsset
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule.NativeDirectory
import com.sickworm.intellij.jugg.project.data.ExternalBuildPrerequisite
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileChangesHandlerTest {

    private val pathManager = JuggPathManager(projectInfo.projectRoot)
    private lateinit var handler: FileChangesHandler

    @Before
    fun init() {
        handler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, logger)
        handler.init(context)
    }

    @Test
    fun testSource() {
        val sourceTestCase = listOf(
            // normal source
            "app/src/main/java/com/example/myapplication/MainActivity.kt" to CompileFile.Type.Kotlin,
            "app/src/main/java/com/example/myapplication/MainActivity2.java" to CompileFile.Type.Java,
            "app/src/main/res/layout/activity_main.xml" to CompileFile.Type.Resource,
            "app/src/main/assets/test/1.jpg" to CompileFile.Type.Asset,
            "app/src/main/AndroidManifest.xml" to CompileFile.Type.AndroidManifest,
            "app_other/src/main/java/com/example/myapplication/MainActivity.kt" to null,
        )

        sourceTestCase.forEach { (path, type) ->
            val file = pathManager.projectDir.resolve(path)
            val result = handler.filter(listOf(file))
            if (type == null) {
                assertTrue(result.isEmpty(), "file: $path")
            } else {
                assertTrue(result.isNotEmpty(), "file: $path")
                assertEquals(result.first().type, type, "file: $path")
            }
        }

    }

    @Test
    fun `does not expand directories outside file change scope`() {
        val outsideDirectory = UnexpectedTraversalDirectory(
            pathManager.projectDir.parentFile.resolve("outside-file-change-scope").path
        )

        assertTrue(handler.filter(listOf(outsideDirectory)).isEmpty())
    }

    @Test
    fun `keeps one changed file when external source is shared by multiple modules`() {
        val sharedRoot = temporaryExternalDirectory("shared-native-source")
        val source = File(sharedRoot, "shared.cpp").apply { writeText("void sharedCall() {}") }
        val modules = listOf("appcommon", "dtmp").associateWith { name ->
            context.applicationModule.copy(
                name = name,
                moduleType = ModuleInfo.Type.Library,
                moduleRootDir = File(pathManager.projectDir, "mp/$name"),
                externalBuildInfos = listOf(ExternalBuildInfo(
                    type = ExternalBuildType.Cpp,
                    inputDirs = listOf(inputDir(sharedRoot, CppSource)),
                    taskPath = ":mp:$name:mergeDebugNativeLibs",
                    assetsOutputDir = null,
                    nativeOutput = File(pathManager.projectDir, "build/$name"),
                )),
            )
        }
        handler.init(context.copy(modules = modules))

        val changed = handler.filter(listOf(source))

        assertEquals(1, changed.size)
        assertEquals(CompileFile.Type.ExternalBuildSource, changed.single().type)
    }

    @Test
    fun `prefers owned Android inputs over another module native directory`() {
        val app = context.applicationModule
        val nativeModule = app.copy(
            name = "appcommon",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = File(pathManager.projectDir, "mp/appcommon"),
            sourceDirs = emptyList(),
            externalBuildInfos = listOf(ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = listOf(inputDir(app.moduleRootDir, NativeDirectory)),
                taskPath = ":mp:appcommon:mergeDebugNativeLibs",
                assetsOutputDir = null,
                nativeOutput = File(pathManager.projectDir, "build/appcommon"),
            )),
        )
        handler.init(context.copy(modules = context.modules + (nativeModule.name to nativeModule)))

        listOf(
            "src/main/java/com/example/myapplication/MainActivity.kt" to CompileFile.Type.Kotlin,
            "src/main/java/com/example/myapplication/MainActivity2.java" to CompileFile.Type.Java,
            "src/main/res/layout/jugg_overlap.xml" to CompileFile.Type.Resource,
            "src/main/assets/jugg_overlap.json" to CompileFile.Type.Asset,
        ).forEach { (path, type) ->
            val file = File(app.moduleRootDir, path)
            withTemporaryFile(file) {
                val changed = handler.filter(listOf(file)).single()
                assertEquals(type, changed.type)
                assertEquals(app.name, changed.module.name)
            }
        }
        val nativeLib = File(app.moduleRootDir, "src/main/jniLibs/arm64-v8a/libjugg_overlap.so")
        withTemporaryFile(nativeLib) {
            assertEquals(CompileFile.Type.NativeLib, handler.filter(listOf(nativeLib)).single().type)
        }
        listOf("src/main/java/native.cc", "native/extra.kt").forEach { path ->
            val file = File(app.moduleRootDir, path)
            withTemporaryFile(file) {
                val changed = handler.filter(listOf(file)).single()
                assertEquals(CompileFile.Type.ExternalBuildSource, changed.type)
                assertEquals(nativeModule.name, changed.module.name)
            }
        }
    }

    @Test
    fun `does not expand module build directories`() {
        val app = context.applicationModule
        val centralizedBuildDir = File(app.projectRootDir, "build/file-change-directory-test")
        val classpathRoot = File(pathManager.juggRootDir, "classpath/root/android_demo_project")
        val module = app.copy(
            buildPathInfo = app.buildPathInfo.copy(
                projectRootDir = classpathRoot,
                moduleRootDir = File(classpathRoot, app.moduleStdPath),
                buildDirRelativePath = centralizedBuildDir.relativeTo(app.projectRootDir).path,
            ),
        )
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertTrue(handler.filter(listOf(UnexpectedTraversalDirectory(centralizedBuildDir.path))).isEmpty())
        assertTrue(handler.filter(listOf(UnexpectedTraversalDirectory(File(app.moduleRootDir, "build").path))).isEmpty())
    }

    @Test
    fun `ignores source changes in conventional and centralized build directories`() {
        val app = context.applicationModule
        val centralizedBuildDir = File(app.projectRootDir, "build/file-change-source-test")
        val conventionalSourceDir = File(app.moduleRootDir, "build/generated/source")
        val centralizedSourceDir = File(centralizedBuildDir, "generated/source")
        val classpathRoot = File(pathManager.juggRootDir, "classpath/root/android_demo_project")
        val module = app.copy(
            sourceDirs = app.sourceDirs + conventionalSourceDir + centralizedSourceDir,
            buildPathInfo = app.buildPathInfo.copy(
                projectRootDir = classpathRoot,
                moduleRootDir = File(classpathRoot, app.moduleStdPath),
                buildDirRelativePath = centralizedBuildDir.relativeTo(app.projectRootDir).path,
            ),
        )
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        withTemporaryFile(File(conventionalSourceDir, "ConventionalGenerated.kt")) {
            withTemporaryFile(File(centralizedSourceDir, "CentralizedGenerated.kt")) {
                assertTrue(handler.filter(listOf(File(conventionalSourceDir, "ConventionalGenerated.kt"))).isEmpty())
                assertTrue(handler.filter(listOf(File(centralizedSourceDir, "CentralizedGenerated.kt"))).isEmpty())
            }
        }
    }

    @Test
    fun `expands directories of modules outside the project directory`() {
        val externalModuleDir = Files.createTempDirectory("jugg-external-module").toFile()
        try {
            val sourceDir = externalModuleDir.resolve("src/main/java")
            val sourceFile = sourceDir.resolve("ExternalSource.kt")
            sourceDir.mkdirs()
            sourceFile.createNewFile()

            val compileContext = context
            val externalModule = compileContext.applicationModule.copy(
                name = "external",
                moduleRootDir = externalModuleDir,
                projectRootDir = externalModuleDir.parentFile,
                sourceDirs = listOf(sourceDir),
                resourceDirs = emptyList(),
                assetsDirs = emptyList(),
                manifestFile = null,
                buildPathInfo = ModuleBuildPathInfo(
                    projectRootDir = externalModuleDir.parentFile,
                    moduleRootDir = externalModuleDir,
                    buildVariant = compileContext.applicationModule.buildVariant,
                    buildDirRelativePath = "",
                ),
            )
            handler.init(compileContext.copy(modules = mapOf(externalModule.name to externalModule)))

            val changedFile = handler.filter(listOf(externalModuleDir)).single()

            assertEquals(sourceFile, changedFile.file)
            assertEquals(externalModule.name, changedFile.module.name)
        } finally {
            externalModuleDir.deleteRecursively()
        }
    }

    @Test
    fun testComposeResource() {
        val app = context.applicationModule
        val composeInfo = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "com.example.test.resources",
            publicResClass = true,
            resourceDirectories = listOf(
                ComposeResourceDirectory("commonMain", File(app.moduleRootDir, "src/main/composeResources")),
                ComposeResourceDirectory("androidMain", File(app.moduleRootDir, "src/main/customComposeResources")),
            ),
            assetRelativePath = "composeResources/com.example.test.resources",
        )
        val module = app.copy(composeResourceInfo = composeInfo)
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFile(
            path = "app/src/main/composeResources/values/strings.xml",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/main/composeResources",
        )
        assertChangedFile(
            path = "app/src/main/customComposeResources/drawable/android_icon.png",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/main/customComposeResources",
        )
    }

    @Test
    fun `detects Flutter and C++ sources configured by external builds`() {
        val app = context.applicationModule
        val flutterRoot = File(app.moduleRootDir, "flutter")
        val cppRoot = File(app.moduleRootDir, "src/main/cpp")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(inputDir(flutterRoot, Dart)),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
            ),
            ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = listOf(inputDir(cppRoot, CppSource, CppHeader)),
                taskPath = ":app:mergeDebugNativeLibs",
                assetsOutputDir = null,
                nativeOutput = File(app.moduleRootDir, "build/intermediates/merged_native_libs/debug/out/lib"),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFile(
            path = "app/flutter/lib/main.dart",
            expectedType = CompileFile.Type.ExternalBuildSource,
            expectedBaseDir = "app/flutter",
        )
        assertChangedFile(
            path = "app/src/main/cpp/native.cpp",
            expectedType = CompileFile.Type.ExternalBuildSource,
            expectedBaseDir = "app/src/main/cpp",
        )
        assertChangedFile(
            path = "app/src/main/cpp/include/native.hpp",
            expectedType = CompileFile.Type.ExternalBuildSource,
            expectedBaseDir = "app/src/main/cpp",
        )
    }

    @Test
    fun `detects files below declared Flutter asset directories and Dart package roots`() {
        val app = context.applicationModule
        val flutterRoot = File(app.moduleRootDir, "flutter-assets")
        val localPackage = File(app.moduleRootDir.parentFile, "jugg-shared-package")
        val localPackageDart = File(File(localPackage, "lib"), "shared.dart")
        val assetFile = File(File(flutterRoot, "assets/images"), "logo.png")
        val pubspec = File(flutterRoot, "pubspec.yaml")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(
                    inputDir(flutterRoot, Dart),
                    inputDir(localPackage, Dart),
                    inputDir(File(flutterRoot, "assets"), FlutterAsset),
                ),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
                configFiles = listOf(pubspec),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFileFile(assetFile)
        assertChangedFileFile(localPackageDart)
        assertChangedFileFile(pubspec)
        assertChangedFileFile(File(flutterRoot, "assets/new-sibling/deep/new.json"))
        assertChangedFile(
            path = "app/flutter-assets/lib/main.dart",
            expectedType = CompileFile.Type.ExternalBuildSource,
            expectedBaseDir = "app/flutter-assets",
        )
        // The Flutter package root stays Dart-only, so an undeclared resource below it is ignored.
        withTemporaryFile(File(flutterRoot, "undeclared/logo.png")) {
            assertTrue(handler.filter(listOf(File(flutterRoot, "undeclared/logo.png"))).isEmpty())
        }
    }

    @Test
    fun `detects new Flutter assets recursively below a declared asset directory`() {
        val app = context.applicationModule
        val flutterRoot = File(app.moduleRootDir, "flutter-new-assets")
        val declaredDirectory = File(flutterRoot, "assets/images").apply { mkdirs() }
        val declaredFile = File(flutterRoot, "assets/splash.png")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(
                    inputDir(flutterRoot, Dart),
                    inputDir(File(flutterRoot, "assets"), FlutterAsset),
                ),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
                excludedDirs = listOf(File(flutterRoot, "build")),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        try {
            assertChangedFileFile(File(declaredDirectory, "new.png"))
            File(declaredDirectory, "logo.png").createNewFile()
            assertChangedFileFile(File(declaredDirectory, "2.0x/logo.png"))
            assertChangedFileFile(declaredFile)
            assertChangedFileFile(File(flutterRoot, "assets/2.0x/splash.png"))
            assertChangedFileFile(File(flutterRoot, "assets/unrelated.png"))
            assertChangedFileFile(File(declaredDirectory, "nested/unrelated.png"))
            withTemporaryFile(File(flutterRoot, "unrelated.png")) {
                assertTrue(handler.filter(listOf(File(flutterRoot, "unrelated.png"))).isEmpty())
            }
            listOf(".dart_tool/assets/generated.png", "build/assets/generated.png").forEach { path ->
                withTemporaryFile(File(flutterRoot, path)) {
                    assertTrue(handler.filter(listOf(File(flutterRoot, path))).isEmpty())
                }
            }
        } finally {
            flutterRoot.deleteRecursively()
        }
    }

    @Test
    fun `refreshes external input directories when compile context is updated`() {
        val app = context.applicationModule
        val oldRoot = temporaryExternalDirectory("jugg-old-external-root")
        val newRoot = temporaryExternalDirectory("jugg-new-external-root")
        try {
            val oldInfo = ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(inputDir(oldRoot, Dart)),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
            )
            val initialModule = app.copy(externalBuildInfos = listOf(oldInfo))
            val updatingContext = UpdatingCompileContext(context, mapOf(initialModule.name to initialModule))
            val newFile = File(newRoot, "lib/new.dart").apply {
                parentFile.mkdirs()
                createNewFile()
            }
            handler.init(updatingContext)

            assertTrue(handler.filter(listOf(newRoot)).isEmpty())

            val refreshedModule = initialModule.copy(externalBuildInfos = listOf(
                oldInfo.copy(inputDirs = listOf(inputDir(newRoot, Dart))),
            ))
            updatingContext.updateModules(mapOf(refreshedModule.name to refreshedModule))

            assertEquals(newFile, handler.filter(listOf(newRoot)).single().file)
        } finally {
            oldRoot.deleteRecursively()
            newRoot.deleteRecursively()
        }
    }

    @Test
    fun `detects assembly and header sources of native external builds`() {
        val app = context.applicationModule
        val cppRoot = File(app.moduleRootDir, "src/main/cpp")
        val module = app.copy(externalBuildInfos = listOf(
            projectCppBuildInfo(cppRoot, app.moduleRootDir),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        listOf("start.S", "arch.asm", "native.inl", "native.tpp").forEach { name ->
            assertChangedFile(
                path = "app/src/main/cpp/$name",
                expectedType = CompileFile.Type.ExternalBuildSource,
                expectedBaseDir = "app/src/main/cpp",
            )
        }
    }

    @Test
    fun `ignores daemon pid and log files below the native input roots`() {
        val app = context.applicationModule
        val dtmpRoot = File(app.moduleRootDir, "DTMP")
        val nativeRoot = File(dtmpRoot, "DTMP")
        val includeRoot = File(nativeRoot, "include")
        val module = app.copy(externalBuildInfos = listOf(
            projectCppBuildInfo(dtmpRoot, app.moduleRootDir, includeRoot, nativeRoot),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        // The configuration root only accepts C/C++ sources and headers, so daemon logs, pids and
        // non-standard native inputs below it stay outside the external build.
        listOf(
            "tools/daemon/tmp/.wapt_dtmp_pid",
            "tools/daemon/logs/wapt-dtmp-daemon-2026-09-16.log",
            "schema/message.proto",
            "prebuilt/libhelper.so",
        ).forEach { path ->
            withTemporaryFile(File(dtmpRoot, path)) {
                assertTrue(handler.filter(listOf(File(dtmpRoot, path))).isEmpty(), path)
            }
        }
        listOf(
            "tools/daemon/helper.cpp",
            "src/main.cpp",
            "include/api.h",
            "DTMP/src/main.cpp",
            "DTMP/schema/message.proto",
            "DTMP/prebuilt/libhelper.so",
            "DTMP/include/api.h",
        ).forEach { path ->
            withTemporaryFile(File(dtmpRoot, path)) {
                val changed = handler.filter(listOf(File(dtmpRoot, path))).single()
                assertEquals(CompileFile.Type.ExternalBuildSource, changed.type, path)
            }
        }
        // A hidden directory below a concrete source directory is never an input.
        withTemporaryFile(File(nativeRoot, ".cache/message.proto")) {
            assertTrue(handler.filter(listOf(File(nativeRoot, ".cache/message.proto"))).isEmpty())
        }
    }

    @Test
    fun `matches every file kind of a concrete native source directory`() {
        val app = context.applicationModule
        val cppRoot = File(app.moduleRootDir, "src/main/cpp")
        val nativeRoot = File(cppRoot, "native")
        val includeRoot = File(cppRoot, "include")
        val module = app.copy(externalBuildInfos = listOf(
            projectCppBuildInfo(cppRoot, app.moduleRootDir, includeRoot, nativeRoot),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        // Metadata confirmed this directory, so non-standard inputs are accepted as well.
        listOf("message.proto", "schema.fbs", "exports.def", "libhelper.a", "libhelper.so",
            "shader.metal", "helper.rs", "helper.zig", "LICENSE").forEach { name ->
            withTemporaryFile(File(nativeRoot, name)) {
                val changed = handler.filter(listOf(File(nativeRoot, name))).single()
                assertEquals(CompileFile.Type.ExternalBuildSource, changed.type, name)
            }
        }
        // The include root keeps header-only matching, and the configuration root keeps C/C++ only.
        withTemporaryFile(File(includeRoot, "LICENSE")) {
            assertTrue(handler.filter(listOf(File(includeRoot, "LICENSE"))).isEmpty())
        }
        listOf("start.S", "native.cpp").forEach { name ->
            withTemporaryFile(File(cppRoot, name)) {
                assertEquals(
                    CompileFile.Type.ExternalBuildSource,
                    handler.filter(listOf(File(cppRoot, name))).single().type,
                    name,
                )
            }
        }
    }

    @Test
    fun `ignores symbolic links below a concrete native source directory`() {
        val app = context.applicationModule
        val cppRoot = File(app.moduleRootDir, "symlink-cpp")
        val nativeRoot = File(cppRoot, "native")
        val outsideRoot = temporaryExternalDirectory("jugg-symlink-target")
        val module = app.copy(externalBuildInfos = listOf(
            projectCppBuildInfo(cppRoot, app.moduleRootDir, null, nativeRoot),
        ))
        try {
            nativeRoot.mkdirs()
            val linkedFile = File(nativeRoot, "linked.proto")
            Files.createSymbolicLink(linkedFile.toPath(), File(outsideRoot, "message.proto").toPath())
            val linkedDir = File(nativeRoot, "linked-dir")
            Files.createSymbolicLink(linkedDir.toPath(), outsideRoot.toPath())
            handler.init(context.copy(modules = context.modules + (module.name to module)))

            assertTrue(handler.filter(listOf(linkedFile)).isEmpty(), "symlinked file")
            assertTrue(handler.filter(listOf(File(linkedDir, "message.proto"))).isEmpty(), "symlinked directory")
            withTemporaryFile(File(nativeRoot, "message.proto")) {
                assertEquals(
                    CompileFile.Type.ExternalBuildSource,
                    handler.filter(listOf(File(nativeRoot, "message.proto"))).single().type,
                )
            }
        } finally {
            cppRoot.deleteRecursively()
            outsideRoot.deleteRecursively()
        }
    }

    @Test
    fun `uses the deepest matched input directory as the base directory`() {
        val app = context.applicationModule
        val dtmpRoot = File(app.moduleRootDir, "DTMP-deep")
        val nativeRoot = File(dtmpRoot, "DTMP")
        val includeRoot = File(nativeRoot, "include")
        val cmakeLists = File(dtmpRoot, "CMakeLists.txt")
        val module = app.copy(externalBuildInfos = listOf(
            projectCppBuildInfo(dtmpRoot, app.moduleRootDir, includeRoot, nativeRoot)
                .copy(configFiles = listOf(cmakeLists)),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        // Both the concrete source directory and its include root accept the header.
        withTemporaryFile(File(includeRoot, "api.h")) {
            val changed = handler.filter(listOf(File(includeRoot, "api.h"))).single()
            assertEquals(includeRoot.canonicalFile, changed.baseDir.canonicalFile)
        }
        withTemporaryFile(File(nativeRoot, "src/main.cpp")) {
            val changed = handler.filter(listOf(File(nativeRoot, "src/main.cpp"))).single()
            assertEquals(nativeRoot.canonicalFile, changed.baseDir.canonicalFile)
        }
        // A configuration file has no input directory and keeps its parent directory.
        withTemporaryFile(File(dtmpRoot, "CMakeLists.txt")) {
            val changed = handler.filter(listOf(File(dtmpRoot, "CMakeLists.txt"))).single()
            assertEquals(dtmpRoot.canonicalFile, changed.baseDir.canonicalFile)
        }
    }

    @Test
    fun `produces one target per external build when several input directories match`() {
        val app = context.applicationModule
        val cppRoot = File(app.moduleRootDir, "single-target-cpp")
        val includeRoot = File(cppRoot, "include")
        val module = app.copy(externalBuildInfos = listOf(
            projectCppBuildInfo(cppRoot, app.moduleRootDir, includeRoot, cppRoot),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        withTemporaryFile(File(includeRoot, "api.h")) {
            assertEquals(1, handler.filter(listOf(File(includeRoot, "api.h"))).size)
        }
    }

    @Test
    fun `keeps a Flutter package root on Dart matching and accepts declared resources`() {
        val app = context.applicationModule
        val flutterRoot = File(app.moduleRootDir, "flutter-rules")
        val emptyAssetDir = File(flutterRoot, "assets/empty")
        val arbDir = File(flutterRoot, "lib/l10n")
        val localPackage = File(app.moduleRootDir.parentFile, "jugg-flutter-rules-package")
        val l10n = File(flutterRoot, "l10n.yaml")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(
                    inputDir(flutterRoot, Dart),
                    inputDir(localPackage, Dart),
                    inputDir(emptyAssetDir, FlutterAsset),
                    inputDir(arbDir, FlutterAsset),
                ),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
                configFiles = listOf(l10n),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertTrue(!emptyAssetDir.exists())
        // The first file in a declared but still missing resource directory is recognized.
        assertChangedFileFile(File(emptyAssetDir, "first.png"))
        assertChangedFileFile(File(arbDir, "app_en.arb"))
        assertChangedFileFile(File(flutterRoot, "lib/main.dart"))
        assertChangedFileFile(File(File(localPackage, "lib"), "shared.dart"))
        assertChangedFileFile(l10n)
        // A resource directly in the package root would widen the whole root to arbitrary files.
        withTemporaryFile(File(flutterRoot, "splash.png")) {
            assertTrue(handler.filter(listOf(File(flutterRoot, "splash.png"))).isEmpty())
        }
        // A Dart package root does not accept arbitrary extensions either.
        withTemporaryFile(File(flutterRoot, "lib/notes.txt")) {
            assertTrue(handler.filter(listOf(File(flutterRoot, "lib/notes.txt"))).isEmpty())
        }
    }

    @Test
    fun `ignores external build directories excluded by metadata`() {
        val app = context.applicationModule
        val sdkRoot = File(app.moduleRootDir.parentFile, "jugg-flutter-sdk")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(inputDir(app.moduleRootDir, Dart)),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
                excludedDirs = listOf(sdkRoot),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        withTemporaryFile(File(sdkRoot, "lib/ui.dart")) {
            assertTrue(handler.filter(listOf(File(sdkRoot, "lib/ui.dart"))).isEmpty())
        }
        assertTrue(handler.filter(listOf(UnexpectedTraversalDirectory(sdkRoot.path))).isEmpty())
    }

    @Test
    fun `ignores removed external build inputs and detects newly added ones`() {
        val app = context.applicationModule
        val flutterRoot = File(app.moduleRootDir, "flutter-removed")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(inputDir(flutterRoot, Dart)),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))
        // A removed path has no input type to restore, so it does not start an external build.
        assertTrue(handler.filter(listOf(File(File(flutterRoot, "lib"), "removed.dart"))).isEmpty())
        // A path that moves into the monitored scope is recognized by its own add event.
        assertChangedFileFile(File(File(flutterRoot, "lib"), "added.dart"))
        // A removed ordinary source keeps its previous behaviour and is not reported.
        assertTrue(handler.filter(listOf(File(app.moduleRootDir, "src/main/java/com/example/Removed.kt"))).isEmpty())
    }

    @Test
    fun `reports a deleted codegen trigger as an external build source`() {
        val app = context.applicationModule
        val cppRoot = File(app.moduleRootDir, "native-idl")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = listOf(inputDir(cppRoot, CppSource, CppHeader)),
                taskPath = ":app:mergeDebugNativeLibs",
                assetsOutputDir = null,
                nativeOutput = File(app.moduleRootDir, "build/intermediates/merged_native_libs/debug/out/lib"),
                prerequisites = listOf(
                    ExternalBuildPrerequisite(
                        taskPath = ":app:compileMidl",
                        triggerGlobs = listOf("**/*.idl.hpp"),
                        generatedSourceDirs = listOf(
                            ExternalBuildGeneratedSourceDir(
                                File(app.moduleRootDir, "build/generated/idl/kotlin/commonMain"),
                                ExternalBuildGeneratedLanguage.Kotlin,
                            ),
                        ),
                    ),
                ),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))
        val removed = File(cppRoot, "modules/chat/idl/Removed.idl.hpp")

        val changed = handler.filter(listOf(removed)).single()

        assertEquals(CompileFile.Type.ExternalBuildSource, changed.type)
        assertEquals(removed, changed.file)
        assertEquals(cppRoot.canonicalFile, changed.baseDir.canonicalFile)
    }

    @Test
    fun `ignores generated external build directories`() {
        val app = context.applicationModule
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(inputDir(app.moduleRootDir, Dart)),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File(app.moduleRootDir, "build/intermediates/flutter/debug"),
                nativeOutput = File(app.moduleRootDir, "build/intermediates/flutter/debug/native.jar"),
            ),
            ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = listOf(inputDir(app.moduleRootDir, CppSource, CppHeader)),
                taskPath = ":app:mergeDebugNativeLibs",
                assetsOutputDir = null,
                nativeOutput = File(app.moduleRootDir, "build/intermediates/merged_native_libs/debug/out/lib"),
            ),
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        listOf(
            "app/.dart_tool/generated.dart",
            "app/.cxx/generated.cpp",
            "app/.externalNativeBuild/generated.cpp",
            "app/build/generated/generated.cpp",
        ).forEach { path ->
            withTemporaryFile(path) {
                assertTrue(handler.filter(listOf(pathManager.projectDir.resolve(path))).isEmpty(), path)
            }
        }

        listOf("app/.dart_tool", "app/.cxx", "app/.externalNativeBuild").forEach { path ->
            assertTrue(handler.filter(listOf(
                UnexpectedTraversalDirectory(pathManager.projectDir.resolve(path).path)
            )).isEmpty(), path)
        }
    }

    @Test
    fun `detects external source when its Gradle task is unsupported`() {
        val app = context.applicationModule
        val flutterRoot = File(app.moduleRootDir, "flutter")
        val module = app.copy(externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(inputDir(flutterRoot, Dart)),
                taskPath = null,
                assetsOutputDir = null,
                nativeOutput = null,
                unsupportedReason = "Flutter task not found",
            )
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFile(
            path = "app/flutter/lib/main.dart",
            expectedType = CompileFile.Type.ExternalBuildSource,
            expectedBaseDir = "app/flutter",
        )
    }

    @Test
    fun `detects first files created under configured missing Compose roots`() {
        val app = context.applicationModule
        val defaultRoot = File(app.moduleRootDir, "src/newCommonMain/composeResources")
        val customRoot = File(app.moduleRootDir, "src/newAndroidMain/customComposeResources")
        defaultRoot.deleteRecursively()
        customRoot.deleteRecursively()
        val module = app.copy(composeResourceInfo = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "com.example.test.resources",
            publicResClass = true,
            resourceDirectories = listOf(
                ComposeResourceDirectory("commonMain", defaultRoot),
                ComposeResourceDirectory("androidMain", customRoot),
            ),
            assetRelativePath = "composeResources/com.example.test.resources",
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertTrue(!defaultRoot.exists())
        assertTrue(!customRoot.exists())
        assertChangedFile(
            path = "app/src/newCommonMain/composeResources/values/first.xml",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/newCommonMain/composeResources",
        )
        assertChangedFile(
            path = "app/src/newAndroidMain/customComposeResources/files/first.txt",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/newAndroidMain/customComposeResources",
        )
    }

    @Test
    fun `keeps unsupported Compose changes in the incremental compile input`() {
        val app = context.applicationModule
        val root = File(app.moduleRootDir, "src/unsupportedMain/composeResources")
        val module = app.copy(composeResourceInfo = ComposeResourceInfo(
            generatorClasspath = emptyList(),
            packageName = "",
            publicResClass = false,
            resourceDirectories = listOf(ComposeResourceDirectory("commonMain", root)),
            assetRelativePath = "",
            supportStatus = ComposeResourceSupportStatus.Unsupported,
            unsupportedReason = "Unsupported Compose resource metadata",
        ))
        handler.init(context.copy(modules = context.modules + (module.name to module)))

        assertChangedFile(
            path = "app/src/unsupportedMain/composeResources/values/first.xml",
            expectedType = CompileFile.Type.ComposeResource,
            expectedBaseDir = "app/src/unsupportedMain/composeResources",
        )
    }

    @Test
    fun testBuild() {
        withTemporaryFile("app/src/main/aidl/ITest.aidl") {
            val buildTestCase = listOf(
                "build.gradle" to true,
                "local.properties" to true,
                "gradle.properties" to true,
                "settings.gradle" to true,
                "app/build.gradle" to true,
                "app/src/main/aidl/ITest.aidl" to true,
                "../build.gradle" to true, // root build file is part of the IDE project
                "app_other/build.gradle" to false, // ignore if not exists
            )

            buildTestCase.forEach { (path, result) ->
                val file = pathManager.projectDir.resolve(path)
                val isMatch = handler.filter(listOf(file)).isNotEmpty()
                assertEquals(result, isMatch, "file: $path")
            }
        }
    }

    @Test
    fun testCustomBuildRules() {
        val rules = """
            dependency.yaml
            /dependency_root.yaml
            **/mologtag/*
            *.config
            !ci.config
        """.trimIndent().split("\n").toList()

        val buildTestCase = listOf(
            "dependency.yaml" to true,
            "app/dependency.yaml" to true,
            "dependency_root.yaml" to true,
            "app/dependency_root.yaml" to false,
            "mologtag/config.yaml" to true,
            "app/mologtag/config.yaml" to true,
            "custom.config" to true,
            "app/custom.config" to true,
            "ci.config" to false,
        )

        // for convenience, create test file and delete after test
        buildTestCase.forEach { (path, _) ->
            val file = pathManager.projectDir.resolve(path)
            file.parentFile.mkdirs()
            file.createNewFile()
        }

        // failed before update rules
        buildTestCase.forEach { (path, _) ->
            val file = pathManager.projectDir.resolve(path)

            val isMatch = handler.filter(listOf(file)).isNotEmpty()
            assertEquals(false, isMatch, "file: $path")
        }

        handler.updateBuildFileRules(rules, emptyList())

        // pass after update rules
        buildTestCase.forEach { (path, result) ->
            val file = pathManager.projectDir.resolve(path)
            val isMatch = handler.filter(listOf(file)).isNotEmpty()
            assertEquals(result, isMatch, "file: $path")
        }
        // also check normal build
        testBuild()

        // for convenience, create test file and delete after test
        buildTestCase.forEach { (path, _) ->
            val file = pathManager.projectDir.resolve(path)
            file.delete()
            if (file.parentFile.listFiles().isNullOrEmpty()) {
                file.parentFile.delete()
            }
        }
    }

    private fun withTemporaryFile(path: String, block: () -> Unit) {
        withTemporaryFile(pathManager.projectDir.resolve(path), block)
    }

    private fun withTemporaryFile(file: File, block: () -> Unit) {
        val existed = file.exists()
        val missingParents = generateSequence(file.parentFile) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
        if (!existed) {
            file.parentFile.mkdirs()
            file.createNewFile()
        }
        try {
            block()
        } finally {
            if (!existed) {
                file.delete()
                missingParents.forEach(File::delete)
            }
        }
    }

    private fun assertChangedFile(path: String, expectedType: CompileFile.Type, expectedBaseDir: String) {
        withTemporaryFile(path) {
            val changed = handler.filter(listOf(pathManager.projectDir.resolve(path))).single()
            assertEquals(expectedType, changed.type)
            assertTrue(changed.type != CompileFile.Type.Resource)
            assertTrue(changed.type != CompileFile.Type.Asset)
            assertEquals(pathManager.projectDir.resolve(expectedBaseDir).canonicalFile, changed.baseDir.canonicalFile)
            assertEquals(context.applicationModule.name, changed.module.name)
        }
    }

    /** Asserts one explicit file outside the module source roots is recognized as an external build source. */
    private fun assertChangedFileFile(file: File) {
        withTemporaryFile(file) {
            val changed = handler.filter(listOf(file)).single()
            assertEquals(CompileFile.Type.ExternalBuildSource, changed.type)
            assertEquals(context.applicationModule.name, changed.module.name)
        }
    }

    private fun projectCppBuildInfo(
        cppRoot: File,
        moduleRootDir: File,
        includeRoot: File? = null,
        nativeSourceRoot: File? = null,
    ) = ExternalBuildInfo(
        type = ExternalBuildType.Cpp,
        inputDirs = buildList {
            add(inputDir(cppRoot, CppSource, CppHeader))
            includeRoot?.let { add(inputDir(it, CppHeader)) }
            nativeSourceRoot?.let { add(inputDir(it, NativeDirectory)) }
        },
        taskPath = ":app:mergeDebugNativeLibs",
        assetsOutputDir = null,
        nativeOutput = File(moduleRootDir, "build/intermediates/merged_native_libs/debug/out/lib"),
    )

    private fun inputDir(
        directory: File,
        vararg rules: ExternalBuildInputFilterRule,
    ) = ExternalBuildInputDir(directory, rules.toSet())

    private class UnexpectedTraversalDirectory(path: String) : File(path) {
        override fun isDirectory() = true

        override fun listFiles(): Array<File> {
            error("Directory outside file change scope should not be expanded")
        }
    }

    private class UpdatingCompileContext(
        private val delegate: ICompileContext,
        initialModules: Map<String, com.sickworm.intellij.jugg.project.data.ModuleInfo>,
    ) : ICompileContext by delegate {
        private val listeners = mutableListOf<OnContextUpdate>()

        override var modules = initialModules
            private set

        override val applicationModule
            get() = modules.values.firstOrNull()

        override fun listenUpdate(listener: OnContextUpdate) {
            listeners.add(listener)
        }

        fun updateModules(modules: Map<String, com.sickworm.intellij.jugg.project.data.ModuleInfo>) {
            this.modules = modules
            listeners.toList().forEach { it() }
        }
    }

    private fun temporaryExternalDirectory(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile()
    }
}
