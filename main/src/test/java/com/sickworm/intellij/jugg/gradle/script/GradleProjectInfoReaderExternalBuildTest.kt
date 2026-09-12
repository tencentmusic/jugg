package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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

    @Suppress("UNCHECKED_CAST")
    private fun readExternalBuildInfos(project: org.gradle.api.Project): List<com.sickworm.intellij.jugg.project.data.ExternalBuildInfo> {
        val reader = GradleProjectInfoReader(project, null, project.projectDir)
        return reader.javaClass.getDeclaredMethod(
            "getExternalBuildInfos",
            org.gradle.api.Project::class.java,
            ModuleInfo::class.java,
        ).run {
            isAccessible = true
            invoke(
                reader,
                project,
                ModuleInfo.virtualModule.copy(buildVariant = "debug"),
            ) as List<com.sickworm.intellij.jugg.project.data.ExternalBuildInfo>
        }
    }
}

/** Test task exposing the Flutter properties read by the project-info script. */
open class TestFlutterCompileTask : DefaultTask() {
    lateinit var sourceDir: File
    lateinit var outputDirectory: File
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

class TestNativeBuildPath(val path: String)
