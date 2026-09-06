package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.gradle.api.DefaultTask
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
    fun `discovers Flutter native archive task and archive file`() {
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
        assertEquals(File(archiveDir, "flutter-native.jar"), info.nativeLibsArchive)
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
        assertNull(info.nativeLibsArchive)
        assertTrue(info.unsupportedReason?.contains("archive task") == true)
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
