package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.info.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputDir
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule.CppHeader
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule.CppSource
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule.Dart
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule.FlutterAsset
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule.NativeDirectory
import com.sickworm.intellij.jugg.project.info.ExternalBuildType
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleProjectInfoReaderExternalBuildTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `discovers Flutter pack archive as a single native output`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val flutterRoot = temporaryFolder.newFolder("flutter")
        val flutterOutput = temporaryFolder.newFolder("flutter-output")
        val archiveDir = temporaryFolder.newFolder("flutter-archive")
        val compileTask = project.tasks.create(
            "compileFlutterBuildDebug",
            TestFlutterCompileTask::class.java,
        ).apply {
            sourceDir = flutterRoot
            outputDirectory = flutterOutput
        }
        val packTask = project.tasks.create(
            "packJniLibsflutterBuildDebug",
            TestFlutterPackTask::class.java,
        ).apply {
            destinationDirectory.set(archiveDir)
            archiveFileName.set("flutter-native.jar")
            dependsOn(compileTask)
        }

        val info = readExternalBuildInfos(project).single()

        assertEquals(ExternalBuildType.Flutter, info.type)
        assertEquals(packTask.path, info.taskPath)
        assertEquals(flutterOutput, info.assetsOutputDir)
        assertEquals(File(archiveDir, "flutter-native.jar"), info.nativeOutput)
        assertTrue(info.isSupported)
    }

    @Test
    fun `discovers C++ merge output as the native output without assets output`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        project.file("cmake/CMakeLists.txt").apply {
            parentFile.mkdirs()
            writeText("cmake_minimum_required(VERSION 3.22)")
        }
        project.extensions.add("android", TestAndroidExtension())
        val mergedDir = temporaryFolder.newFolder("merged-native-libs")
        val mergeTask = project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = mergedDir
        }

        val info = readExternalBuildInfos(project).single()

        assertEquals(ExternalBuildType.Cpp, info.type)
        assertEquals(mergeTask.path, info.taskPath)
        assertNull(info.assetsOutputDir)
        assertEquals(mergedDir, info.nativeOutput)
        assertTrue(info.isSupported)
    }

    @Test
    fun `rejects Flutter pack task without compile dependency`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = temporaryFolder.newFolder("flutter")
            outputDirectory = temporaryFolder.newFolder("flutter-output")
        }
        project.tasks.create("packJniLibsflutterBuildDebug", TestFlutterPackTask::class.java).apply {
            destinationDirectory.set(temporaryFolder.newFolder("flutter-archive"))
            archiveFileName.set("flutter-native.jar")
        }

        val info = readExternalBuildInfos(project).single()

        assertNull(info.taskPath)
        assertNull(info.nativeOutput)
        assertTrue(info.unsupportedReason?.contains("native task") == true)
    }

    @Test
    fun `discovers Flutter jniLibs copy directory as a single native output`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val flutterOutput = temporaryFolder.newFolder("flutter-output")
        val jniLibsDir = temporaryFolder.newFolder("flutter-jniLibs")
        val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = temporaryFolder.newFolder("flutter")
            outputDirectory = flutterOutput
        }
        val copyTask = project.tasks.create("copyJniLibsflutterBuildDebug", TestFlutterCopyTask::class.java).apply {
            destinationDir.set(jniLibsDir)
            // Flutter registers this dependency lazily through tasks.matching, not through a direct dependsOn.
            dependsOn(project.tasks.matching { it.name == compileTask.name })
        }

        val info = readExternalBuildInfos(project).single()

        assertEquals(ExternalBuildType.Flutter, info.type)
        assertEquals(copyTask.path, info.taskPath)
        assertEquals(flutterOutput, info.assetsOutputDir)
        assertEquals(jniLibsDir, info.nativeOutput)
        assertTrue(info.isSupported)
    }

    @Test
    fun `rejects Flutter jniLibs copy task without compile dependency`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = temporaryFolder.newFolder("flutter")
            outputDirectory = temporaryFolder.newFolder("flutter-output")
        }
        project.tasks.create("copyJniLibsflutterBuildDebug", TestFlutterCopyTask::class.java).apply {
            destinationDir.set(temporaryFolder.newFolder("flutter-jniLibs"))
        }

        val info = readExternalBuildInfos(project).single()

        assertNull(info.taskPath)
        assertNull(info.nativeOutput)
        assertTrue(info.unsupportedReason?.contains("native task") == true)
    }

    @Test
    fun `rejects Flutter jniLibs copy task without a native output location`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = temporaryFolder.newFolder("flutter")
            outputDirectory = temporaryFolder.newFolder("flutter-output")
        }
        project.tasks.create("copyJniLibsflutterBuildDebug", TestFlutterCopyTask::class.java).apply {
            dependsOn(compileTask)
        }

        val info = readExternalBuildInfos(project).single()

        assertNull(info.taskPath)
        assertNull(info.nativeOutput)
        assertTrue(info.unsupportedReason?.contains("native output") == true)
    }

    @Test
    fun `watches Flutter task inputs without watching the SDK, generated outputs or pub cache`() {
        val localPackage = Files.createTempDirectory("jugg-local-package").toRealPath().toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
            val appDir = temporaryFolder.newFolder("app").canonicalFile
            val flutterRoot = temporaryFolder.newFolder("flutter").canonicalFile
            val sameProjectPackage = temporaryFolder.newFolder("same-project-package").canonicalFile
            val sdkRoot = temporaryFolder.newFolder("flutter-sdk").canonicalFile
            val generatedDart = writeFile(File(File(appDir, "build/generated"), "Generated.dart"))
            val sdkDart = writeFile(File(File(sdkRoot, "bin/cache/pkg/sky_engine/lib/ui"), "ui.dart"))
            val cachedDart = writeFile(File(File(flutterRoot, ".dart_tool/flutter_build"), "generated.dart"))
            val appDart = writeFile(File(File(flutterRoot, "lib"), "main.dart"))
            val assetFile = writeFile(File(File(flutterRoot, "assets"), "logo.png"))
            val pubspec = writeFile(File(flutterRoot, "pubspec.yaml"))
            val localPackageDart = writeFile(File(File(localPackage, "lib"), "shared.dart"))
            val sameProjectPackageDart = writeFile(File(File(sameProjectPackage, "lib"), "shared.dart"))
            writeFile(File(localPackage, "pubspec.yaml"))
            writeFile(File(sameProjectPackage, "pubspec.yaml"))
            writeFile(File(sdkRoot, "pubspec.yaml"))
            writeFile(File(temporaryFolder.root, "local.properties"), "flutter.sdk=${sdkRoot.path}\n")
            val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
                sourceDir = flutterRoot
                outputDirectory = temporaryFolder.newFolder("flutter-output")
                sourceFiles = project.files(
                    appDart, assetFile, pubspec, localPackageDart, sameProjectPackageDart,
                    generatedDart, sdkDart, cachedDart,
                )
            }
            project.tasks.create("packJniLibsflutterBuildDebug", TestFlutterPackTask::class.java).apply {
                destinationDirectory.set(temporaryFolder.newFolder("flutter-archive"))
                archiveFileName.set("flutter-native.jar")
                dependsOn(compileTask)
            }
            val moduleInfo = appModuleInfo(appDir)

            val info = readExternalBuildInfos(project, moduleInfo).single()

            // Every package root only accepts Dart sources, so generated and cached Dart files are
            // dropped with their directories instead of widening the root to arbitrary files.
            assertTrue(File(flutterRoot, "lib") in info.inputDirectories(), "Dart input parents: ${info.inputDirs}")
            assertTrue(localPackage in info.inputDirectories())
            assertTrue(sameProjectPackage in info.inputDirectories())
            assertTrue(
                File(flutterRoot, "assets").canonicalFile in info.inputDirectories(),
                "a task resource strictly below a package root keeps its own directory: ${info.inputDirs}",
            )
            assertTrue(generatedDart.parentFile.canonicalFile !in info.inputDirectories())
            assertTrue(cachedDart.parentFile.canonicalFile !in info.inputDirectories())
            assertEquals(setOf(Dart), info.ruleSetsAt(flutterRoot.canonicalFile).single())
            assertEquals(setOf(FlutterAsset), info.ruleSetsAt(File(flutterRoot, "assets").canonicalFile).single())
            assertEquals(
                setOf(pubspec, File(localPackage, "pubspec.yaml").canonicalFile,
                    File(sameProjectPackage, "pubspec.yaml").canonicalFile),
                info.configFiles.canonical().toSet(),
            )
            assertTrue(sdkRoot in info.excludedDirs.canonical(), "SDK must be excluded: ${info.excludedDirs}")
            assertTrue(File(flutterRoot, ".dart_tool").canonicalFile in info.excludedDirs.canonical())
            assertTrue(File(appDir, "build").canonicalFile in info.excludedDirs.canonical())
        } finally {
            localPackage.deleteRecursively()
        }
    }

    @Test
    fun `reads declared Flutter resource directories and keeps single files on task inputs`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val flutterRoot = temporaryFolder.newFolder("flutter-pubspec-assets").canonicalFile
        val declaredDirectory = File(flutterRoot, "assets/images").apply { mkdirs() }
        val declaredFile = File(flutterRoot, "assets/splash.png")
        writeFile(declaredFile)
        writeFile(
            File(flutterRoot, "pubspec.yaml"),
            """
                flutter:
                  assets:
                    - assets/images/
                    - assets/splash.png
                    - "assets/quoted image.webp" # Keep inline comments outside the path.
                    - path: assets/flavored/
                      flavors:
                        - free
                    - assets/other.png
                    - /absolute/assets/
                    - ../outside/
                    - .dart_tool/generated.png
                    - assets/back/../images/
            """.trimIndent(),
        )
        writeFile(File(flutterRoot, "l10n.yaml"), "arb-dir: lib/l10n\ntemplate-arb-file: app_en.arb\n")
        val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = flutterRoot
            outputDirectory = temporaryFolder.newFolder("flutter-output-pubspec-assets")
            sourceFiles = project.files(declaredFile)
        }
        project.tasks.create("packJniLibsflutterBuildDebug", TestFlutterPackTask::class.java).apply {
            destinationDirectory.set(temporaryFolder.newFolder("flutter-archive-pubspec-assets"))
            archiveFileName.set("flutter-native.jar")
            dependsOn(compileTask)
        }
        val moduleInfo = appModuleInfo(project.projectDir)

        val info = readExternalBuildInfos(project, moduleInfo).single()

        // Only directory declarations become resource roots: single files, fonts and shaders keep
        // relying on the Flutter task inputs, and escaping or excluded declarations are rejected.
        assertEquals(
            listOf(
                flutterRoot,
                File(flutterRoot, "assets/splash.png").parentFile.canonicalFile,
                declaredDirectory.canonicalFile,
                File(flutterRoot, "assets/flavored").canonicalFile,
                File(flutterRoot, "lib/l10n").canonicalFile,
            ).sortedBy { it.path },
            info.inputDirectories().sortedBy { it.path },
        )
        assertEquals(setOf(Dart), info.ruleSetsAt(flutterRoot).single())
        assertTrue(info.ruleSetsAt(declaredDirectory.canonicalFile).single() == setOf(FlutterAsset))
        assertTrue(File(flutterRoot, "lib/l10n").canonicalFile in info.inputDirectories())
        assertTrue(File(flutterRoot, "pubspec.yaml").canonicalFile in info.configFiles.canonical())
        assertTrue(File(flutterRoot, "l10n.yaml").canonicalFile in info.configFiles.canonical())
    }

    @Test
    fun `keeps a declared but missing Flutter resource directory and rejects symbolic links`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val flutterRoot = temporaryFolder.newFolder("flutter-empty-assets").canonicalFile
        val missingDirectory = File(flutterRoot, "assets/missing")
        val linkedTarget = temporaryFolder.newFolder("flutter-linked-assets")
        val linkedDirectory = File(flutterRoot, "assets/linked")
        linkedDirectory.parentFile.mkdirs()
        Files.createSymbolicLink(linkedDirectory.toPath(), linkedTarget.toPath())
        writeFile(File(flutterRoot, "pubspec.yaml"), "flutter:\n  assets:\n    - assets/missing/\n    - assets/linked/\n")
        val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = flutterRoot
            outputDirectory = temporaryFolder.newFolder("flutter-output-empty-assets")
            sourceFiles = project.files(emptyList<File>())
        }
        project.tasks.create("packJniLibsflutterBuildDebug", TestFlutterPackTask::class.java).apply {
            destinationDirectory.set(temporaryFolder.newFolder("flutter-archive-empty-assets"))
            archiveFileName.set("flutter-native.jar")
            dependsOn(compileTask)
        }
        val moduleInfo = appModuleInfo(project.projectDir)

        val info = readExternalBuildInfos(project, moduleInfo).single()

        assertTrue(!missingDirectory.exists())
        assertTrue(missingDirectory.canonicalFile in info.inputDirectories(), "empty declared directory: ${info.inputDirs}")
        assertTrue(linkedDirectory.canonicalFile !in info.inputDirectories(), "symlinked declaration")
        assertEquals(listOf(flutterRoot, missingDirectory.canonicalFile).sortedBy { it.path }, info.inputDirectories())
    }

    @Test
    fun `keeps the broad Flutter source root when the task inputs are unavailable`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val flutterRoot = temporaryFolder.newFolder("flutter-no-inputs")
        writeFile(File(flutterRoot, "pubspec.yaml"))
        val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
            sourceDir = flutterRoot
            outputDirectory = temporaryFolder.newFolder("flutter-output-no-inputs")
            sourceFiles = project.files(emptyList<File>())
        }
        project.tasks.create("packJniLibsflutterBuildDebug", TestFlutterPackTask::class.java).apply {
            destinationDirectory.set(temporaryFolder.newFolder("flutter-archive-no-inputs"))
            archiveFileName.set("flutter-native.jar")
            dependsOn(compileTask)
        }

        val info = readExternalBuildInfos(project).single()

        assertEquals(listOf(flutterRoot.canonicalFile), info.inputDirectories())
        assertEquals(setOf(Dart), info.inputDirs.single().filterRules)
        assertEquals(listOf(File(flutterRoot, "pubspec.yaml").canonicalFile), info.configFiles.canonical())
    }

    @Test
    fun `reads native configuration roots, metadata source directories and include roots`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val cmakeDir = File(temporaryFolder.root, "cmake")
        val cmakeLists = writeFile(File(cmakeDir, "CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22)\n")
        val toolchainCmake = writeFile(File(cmakeDir, "toolchain.cmake"), "# toolchain\n")
        writeFile(File(File(cmakeDir, "modules"), "helpers.cmake"), "# helpers\n")
        project.extensions.add("android", TestAndroidExtension())
        project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = temporaryFolder.newFolder("merged-native-libs")
        }
        val moduleRoot = temporaryFolder.root.canonicalFile
        writeCmakeReply(
            File(temporaryFolder.root, ".cxx/cmake/debug/arm64-v8a"), "debug",
            sources = listOf(
                // Below the configuration root: a concrete source directory is confirmed.
                File(moduleRoot, "cmake/native/target.cpp").path,
                // The configuration root itself: the root rule already covers it.
                File(moduleRoot, "cmake/root_source.cpp").path,
                // Outside the configuration root: still a concrete source directory.
                File(moduleRoot, "external/native.cpp").path,
                // A metadata header source keeps its directory on header-only matching.
                File(moduleRoot, "cmake/include/api.h").path,
            ),
            includes = listOf(File(moduleRoot, "cmake/shared-include").path),
        )
        writeCmakeReply(
            File(temporaryFolder.root, ".cxx/cmake/release/arm64-v8a"), "release",
            sources = listOf("/release/stale.cpp"),
            includes = emptyList(),
        )
        val moduleInfo = appModuleInfo(moduleRoot)

        val info = readExternalBuildInfos(project, moduleInfo).single()

        assertEquals(ExternalBuildType.Cpp, info.type)
        assertEquals(
            setOf(cmakeLists, toolchainCmake, File(File(cmakeDir, "modules"), "helpers.cmake")).canonical().toSet(),
            info.configFiles.canonical().toSet(),
        )
        assertEquals(setOf(CppSource, CppHeader), info.ruleSetsAt(cmakeDir.canonicalFile).single())
        assertEquals(setOf(NativeDirectory), info.ruleSetsAt(File(moduleRoot, "cmake/native")).single())
        assertEquals(setOf(NativeDirectory), info.ruleSetsAt(File(moduleRoot, "external")).single())
        assertEquals(setOf(CppHeader), info.ruleSetsAt(File(moduleRoot, "cmake/include")).single())
        assertEquals(setOf(CppHeader), info.ruleSetsAt(File(moduleRoot, "cmake/shared-include")).single())
        // A stale reply of another variant never contributes inputs.
        assertTrue(File("/release").canonicalFile !in info.inputDirectories())
        assertNull(info.unsupportedReason)
    }

    @Test
    fun `resolves relative CMake target sources from the codemodel source root`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val moduleRoot = temporaryFolder.root.canonicalFile
        val cmakeDir = File(moduleRoot, "cmake")
        writeFile(File(cmakeDir, "CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22)\n")
        project.extensions.add("android", TestAndroidExtension())
        project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = temporaryFolder.newFolder("merged-native-relative-source")
        }
        writeCmakeReply(
            File(moduleRoot, ".cxx/cmake/debug/arm64-v8a"), "debug",
            sources = listOf("shared/shared_value.cc"),
            includes = emptyList(),
            sourceRoot = cmakeDir,
        )

        val info = readExternalBuildInfos(project, appModuleInfo(moduleRoot)).single()

        assertEquals(setOf(NativeDirectory), info.ruleSetsAt(File(cmakeDir, "shared")).single())
    }

    @Test
    fun `keeps every rule set of one directory and never compacts a child into its parent`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val moduleRoot = temporaryFolder.root.canonicalFile
        val cmakeDir = File(moduleRoot, "cmake")
        writeFile(File(cmakeDir, "CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22)\n")
        project.extensions.add("android", TestAndroidExtension())
        project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = temporaryFolder.newFolder("merged-native-rule-sets")
        }
        writeCmakeReply(
            File(moduleRoot, ".cxx/cmake/debug/arm64-v8a"), "debug",
            // The include root equals the configuration root, and one target source lives there too.
            sources = listOf(File(cmakeDir, "native.cpp").path),
            includes = listOf(cmakeDir.path),
        )
        val moduleInfo = appModuleInfo(moduleRoot)

        val info = readExternalBuildInfos(project, moduleInfo).single()

        assertEquals(
            listOf(setOf(CppHeader), setOf(CppSource, CppHeader)),
            info.ruleSetsAt(cmakeDir),
            "the configuration root and the include root keep separate records: ${info.inputDirs}",
        )
        assertEquals(2, info.inputDirs.size, "inputs: ${info.inputDirs}")
    }

    @Test
    fun `excludes toolchain, build and staging directories`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val moduleRoot = temporaryFolder.root.canonicalFile
        val sdkRoot = temporaryFolder.newFolder("android-sdk").canonicalFile
        val ndkRoot = temporaryFolder.newFolder("android-ndk").canonicalFile
        val stagingDir = temporaryFolder.newFolder("native-staging").canonicalFile
        writeFile(File(temporaryFolder.root, "local.properties"),
            "sdk.dir=${sdkRoot.path}\nndk.dir=${ndkRoot.path}\n")
        writeFile(File(moduleRoot, "cmake/CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22)\n")
        project.extensions.add("android", TestAndroidExtension(stagingDir))
        project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = temporaryFolder.newFolder("merged-native-toolchain")
        }
        val moduleInfo = appModuleInfo(moduleRoot)

        val info = readExternalBuildInfos(project, moduleInfo).single()

        val excluded = info.excludedDirs.canonical()
        assertTrue(sdkRoot in excluded, "SDK: ${info.excludedDirs}")
        assertTrue(File(sdkRoot, "cmake") in excluded)
        assertTrue(ndkRoot in excluded, "NDK: ${info.excludedDirs}")
        assertTrue(stagingDir in excluded, "staging: ${info.excludedDirs}")
        assertTrue(File(moduleRoot, "build").canonicalFile in excluded)
        assertTrue(moduleInfo.buildPathInfo.buildDir.canonicalFile in excluded)
        assertTrue(File(moduleRoot, ".cxx").canonicalFile in excluded)
        assertTrue(File(moduleRoot, ".externalNativeBuild").canonicalFile in excluded)
        assertTrue(project.gradle.gradleUserHomeDir.canonicalFile in excluded, "Gradle cache: ${info.excludedDirs}")
    }

    @Test
    fun `ignores metadata inputs the configuration root already covers`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val moduleRoot = temporaryFolder.root.canonicalFile
        val cmakeDir = File(moduleRoot, "cmake")
        writeFile(File(cmakeDir, "CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22)\n")
        project.extensions.add("android", TestAndroidExtension())
        project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = temporaryFolder.newFolder("merged-native-ignored")
        }
        writeCmakeReply(
            File(moduleRoot, ".cxx/cmake/debug/arm64-v8a"), "debug",
            sources = listOf(
                File(cmakeDir, "root_source.cpp").path,
                File(cmakeDir, "root_generated.proto").path,
            ),
            includes = emptyList(),
        )
        val moduleInfo = appModuleInfo(moduleRoot)

        val info = readExternalBuildInfos(project, moduleInfo).single()

        // Inputs the configuration root already covers never widen it, and they never mark the
        // external build unsupported.
        assertEquals(listOf(setOf(CppSource, CppHeader)), info.ruleSetsAt(cmakeDir))
        assertEquals(listOf(cmakeDir.canonicalFile), info.inputDirectories())
        assertNull(info.unsupportedReason)
    }

    /** All rule sets of one input directory, so a duplicated record can not hide a missing one. */
    private fun ExternalBuildInfo.ruleSetsAt(directory: File): List<Set<ExternalBuildInputFilterRule>> {
        return inputDirs.filter { it.directory.canonicalFile == directory.canonicalFile }
            .map { it.filterRules }
            .distinct()
    }

    private fun ExternalBuildInfo.inputDirectories(): List<File> {
        return inputDirs.map { it.directory.canonicalFile }.distinct()
    }

    private fun appModuleInfo(moduleRoot: File): ModuleInfo = ModuleInfo.virtualModule.copy(
        name = "app",
        moduleRootDir = moduleRoot,
        projectRootDir = moduleRoot,
        buildVariant = "debug",
        buildPathInfo = ModuleBuildPathInfo(moduleRoot, moduleRoot, "debug", buildDirRelativePath = "build"),
    )

    private fun Iterable<File>.canonical(): List<File> = map { it.canonicalFile }

    /** Writes a minimal CMake File API reply holding one target with the requested sources and includes. */
    private fun writeCmakeReply(
        replyParent: File,
        variant: String,
        sources: List<String>,
        includes: List<String>,
        sourceRoot: File? = null,
    ) {
        val replyDir = File(replyParent, ".cmake/api/v1/reply")
        replyDir.mkdirs()
        val paths = sourceRoot?.let { "\"paths\":{\"source\":\"${it.path}\"}," }.orEmpty()
        writeFile(
            File(replyDir, "codemodel-v2-$variant.json"),
            """{$paths"configurations":[{"name":"$variant","targets":[{"name":"app","jsonFile":"target-app-$variant.json"}]}]}""",
        )
        val sourceJson = sources.joinToString(",") { """{"path":"$it"}""" }
        val includeJson = includes.joinToString(",") { """{"path":"$it"}""" }
        writeFile(
            File(replyDir, "target-app-$variant.json"),
            """{"name":"app","sources":[$sourceJson],""" +
                """"compileGroups":[{"includes":[$includeJson]}]}""",
        )
    }

    private fun writeFile(file: File, content: String = ""): File {
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.canonicalFile
    }

    @Suppress("UNCHECKED_CAST")
    private fun readExternalBuildInfos(
        project: org.gradle.api.Project,
        moduleInfo: ModuleInfo = ModuleInfo.virtualModule.copy(buildVariant = "debug"),
    ): List<ExternalBuildInfo> {
        val reader = GradleProjectInfoReader(project, null, project.projectDir)
        return reader.javaClass.getDeclaredMethod(
            "getExternalBuildInfos",
            org.gradle.api.Project::class.java,
            ModuleInfo::class.java,
        ).run {
            isAccessible = true
            invoke(reader, project, moduleInfo) as List<ExternalBuildInfo>
        }
    }
}

/** Test task exposing the Flutter properties read by the project-info script. */
open class TestFlutterCompileTask : DefaultTask() {
    lateinit var sourceDir: File
    lateinit var outputDirectory: File

    /** Flutter task inputs: the depfile-derived sources plus the explicit pubspec inputs. */
    lateinit var sourceFiles: FileCollection
}

/** Test Jar task representing Flutter's native library packaging task. */
open class TestFlutterPackTask : Jar()

/** Test task representing Flutter's jniLibs staging task. */
abstract class TestFlutterCopyTask : DefaultTask() {
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty
}

/** Test task representing AGP's native library merge task. */
open class TestNativeMergeTask : DefaultTask() {
    lateinit var outputDir: File
}

/** Minimal Android extension exposing the external native build paths read by the project-info script. */
class TestAndroidExtension(private val buildStagingDirectory: File? = null) {
    fun getExternalNativeBuild() = TestExternalNativeBuild(buildStagingDirectory)
}

class TestExternalNativeBuild(private val buildStagingDirectory: File?) {
    fun getCmake() = TestNativeBuildPath("cmake/CMakeLists.txt", buildStagingDirectory)
}

class TestNativeBuildPath(val path: String, val buildStagingDirectory: Any? = null)
