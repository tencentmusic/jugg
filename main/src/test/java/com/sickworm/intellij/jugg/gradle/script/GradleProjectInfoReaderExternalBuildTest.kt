package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
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
    fun `reads Flutter task inputs without watching the SDK, generated outputs or pub cache`() {
        val localPackage = Files.createTempDirectory("jugg-local-package").toRealPath().toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
            val appDir = temporaryFolder.newFolder("app").canonicalFile
            val flutterRoot = temporaryFolder.newFolder("flutter").canonicalFile
            val sdkRoot = temporaryFolder.newFolder("flutter-sdk").canonicalFile
            val generatedDart = writeFile(File(File(appDir, "build/generated"), "Generated.dart"))
            val sdkDart = writeFile(File(File(sdkRoot, "bin/cache/pkg/sky_engine/lib/ui"), "ui.dart"))
            val cachedDart = writeFile(File(File(flutterRoot, ".dart_tool/flutter_build"), "generated.dart"))
            val appDart = writeFile(File(File(flutterRoot, "lib"), "main.dart"))
            val assetFile = writeFile(File(File(flutterRoot, "assets"), "logo.png"))
            val pubspec = writeFile(File(flutterRoot, "pubspec.yaml"))
            val localPackageDart = writeFile(File(File(localPackage, "lib"), "shared.dart"))
            writeFile(File(localPackage, "pubspec.yaml"))
            writeFile(File(sdkRoot, "pubspec.yaml"))
            writeFile(File(temporaryFolder.root, "local.properties"), "flutter.sdk=${sdkRoot.path}\n")
            val compileTask = project.tasks.create("compileFlutterBuildDebug", TestFlutterCompileTask::class.java).apply {
                sourceDir = flutterRoot
                outputDirectory = temporaryFolder.newFolder("flutter-output")
                sourceFiles = project.files(
                    appDart, assetFile, pubspec, localPackageDart, generatedDart, sdkDart, cachedDart,
                )
            }
            project.tasks.create("packJniLibsflutterBuildDebug", TestFlutterPackTask::class.java).apply {
                destinationDirectory.set(temporaryFolder.newFolder("flutter-archive"))
                archiveFileName.set("flutter-native.jar")
                dependsOn(compileTask)
            }
            val moduleInfo = ModuleInfo.virtualModule.copy(
                name = "app",
                moduleRootDir = appDir,
                projectRootDir = temporaryFolder.root.canonicalFile,
                buildVariant = "debug",
                buildPathInfo = ModuleBuildPathInfo(appDir, appDir, "debug", buildDirRelativePath = "build"),
            )

            val info = readExternalBuildInfos(project, moduleInfo).single()

            assertEquals(
                setOf(appDart, assetFile, localPackageDart).canonical().toSet(),
                info.inputFiles.canonical().toSet(),
            )
            assertEquals(setOf(pubspec), info.configFiles.canonical().toSet())

            assertTrue(
                localPackage in info.sourceDirs.canonical(),
                "local path package root should be watched: ${info.sourceDirs}",
            )
            assertTrue(sdkRoot in info.excludedDirs.canonical(), "SDK must be excluded: ${info.excludedDirs}")
            assertTrue(File(flutterRoot, ".dart_tool").canonicalFile in info.excludedDirs.canonical())
            assertTrue(File(appDir, "build").canonicalFile in info.excludedDirs.canonical())
        } finally {
            localPackage.deleteRecursively()
        }
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

        assertEquals(emptyList(), info.inputFiles)
        assertEquals(listOf(flutterRoot.canonicalFile), info.sourceDirs.canonical())
        assertEquals(listOf(File(flutterRoot, "pubspec.yaml").canonicalFile), info.configFiles.canonical())
    }

    @Test
    fun `discovers CMake configuration inputs and native metadata of the current variant`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
        val cmakeDir = File(temporaryFolder.root, "cmake")
        val cmakeLists = writeFile(File(cmakeDir, "CMakeLists.txt"), "cmake_minimum_required(VERSION 3.22)\n")
        val toolchainCmake = writeFile(File(cmakeDir, "toolchain.cmake"), "# toolchain\n")
        writeFile(File(File(cmakeDir, "modules"), "helpers.cmake"), "# helpers\n")
        project.extensions.add("android", TestAndroidExtension())
        project.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java).apply {
            outputDir = temporaryFolder.newFolder("merged-native-libs")
        }
        writeCmakeReply(File(temporaryFolder.root, ".cxx/cmake/debug/arm64-v8a"), "debug", "/debug/shared.cpp")
        writeCmakeReply(File(temporaryFolder.root, ".cxx/cmake/release/arm64-v8a"), "release", "/release/stale.cpp")
        val moduleInfo = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleRootDir = temporaryFolder.root.canonicalFile,
            projectRootDir = temporaryFolder.root.canonicalFile,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(
                temporaryFolder.root.canonicalFile,
                temporaryFolder.root.canonicalFile,
                "debug",
                buildDirRelativePath = "build",
            ),
        )

        val info = readExternalBuildInfos(project, moduleInfo).single()

        assertEquals(ExternalBuildType.Cpp, info.type)
        assertEquals(
            setOf(cmakeLists, toolchainCmake, File(File(cmakeDir, "modules"), "helpers.cmake"))
                .canonical().toSet(),
            info.configFiles.canonical().toSet(),
        )
        assertTrue(File("/debug/shared.cpp") in info.inputFiles, "debug target sources: ${info.inputFiles}")
        assertTrue(File("/debug/include") in info.sourceDirs, "include roots: ${info.sourceDirs}")
        assertTrue(File("/release/stale.cpp") !in info.inputFiles, "stale variant reply must be ignored")
        assertTrue(File(temporaryFolder.root, ".cxx").canonicalFile in info.excludedDirs.canonical())
    }

    private fun Iterable<File>.canonical(): List<File> = map { it.canonicalFile }

    /** Writes a minimal CMake File API reply holding one target with one source and one include root. */
    private fun writeCmakeReply(replyParent: File, variant: String, sourcePath: String) {
        val replyDir = File(replyParent, ".cmake/api/v1/reply")
        replyDir.mkdirs()
        writeFile(
            File(replyDir, "codemodel-v2-$variant.json"),
            """{"configurations":[{"name":"$variant","targets":[{"name":"app","jsonFile":"target-app-$variant.json"}]}]}""",
        )
        writeFile(
            File(replyDir, "target-app-$variant.json"),
            """{"name":"app","sources":[{"path":"$sourcePath"}],""" +
                """"compileGroups":[{"includes":[{"path":"/${variant}/include"}]}]}""",
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
    ): List<com.sickworm.intellij.jugg.project.data.ExternalBuildInfo> {
        val reader = GradleProjectInfoReader(project, null, project.projectDir)
        return reader.javaClass.getDeclaredMethod(
            "getExternalBuildInfos",
            org.gradle.api.Project::class.java,
            ModuleInfo::class.java,
        ).run {
            isAccessible = true
            invoke(reader, project, moduleInfo) as List<com.sickworm.intellij.jugg.project.data.ExternalBuildInfo>
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
class TestAndroidExtension {
    fun getExternalNativeBuild() = TestExternalNativeBuild()
}

class TestExternalNativeBuild {
    fun getCmake() = TestNativeBuildPath("cmake/CMakeLists.txt")
}

class TestNativeBuildPath(val path: String, val buildStagingDirectory: Any? = null)
