package com.sickworm.intellij.jugg.compiler.external

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.project.data.ExternalBuildGeneratedLanguage
import com.sickworm.intellij.jugg.project.data.ExternalBuildGeneratedSourceDir
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputDir
import com.sickworm.intellij.jugg.project.data.ExternalBuildInputFilterRule
import com.sickworm.intellij.jugg.project.data.ExternalBuildPrerequisite
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalBuildPrerequisiteTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matches nested trigger files and ignores unrelated sources`() {
        val root = temporaryFolder.newFolder("native")
        val idl = File(root, "modules/chat/idl/Chat.idl.hpp")
        val source = File(root, "src/chat_impl.cc")
        val buildInfo = cppBuildInfo(root)

        assertTrue(matchesExternalBuildTriggerGlob(idl, "**/*.idl.hpp", root))
        assertFalse(matchesExternalBuildTriggerGlob(source, "**/*.idl.hpp", root))
        assertEquals(listOf(buildInfo.prerequisites.single()), matchedPrerequisites(buildInfo, listOf(idl, source)))
        assertTrue(matchedPrerequisites(buildInfo, listOf(source)).isEmpty())
    }

    @Test
    fun `matches a deleted trigger path that no longer exists`() {
        val root = temporaryFolder.newFolder("native-deleted")
        val removed = File(root, "modules/chat/idl/Removed.idl.hpp")
        val buildInfo = cppBuildInfo(root)
        val module = ModuleInfo.virtualModule.copy(
            name = "app",
            externalBuildInfos = listOf(buildInfo),
        )

        assertFalse(removed.exists())
        assertTrue(matchesExternalBuildTriggerGlob(removed, "**/*.idl.hpp", root))
        val target = resolveDeletedPrerequisiteTrigger(listOf(module), removed)
        assertEquals(root, target?.matchedInputDir?.directory)
        assertEquals(buildInfo.taskPath, target?.buildInfo?.taskPath)
    }

    @Test
    fun `collects declared Kotlin sources and ignores other generated files`() {
        val module = ModuleInfo.virtualModule.copy(name = "app")
        val kotlinDir = temporaryFolder.newFolder("generated-kotlin")
        val javaDir = temporaryFolder.newFolder("generated-java")
        File(kotlinDir, "pkg/Expect.kt").apply {
            parentFile.mkdirs()
            writeText("package pkg")
        }
        File(kotlinDir, "pkg/native.cc").apply {
            parentFile.mkdirs()
            writeText("int unused;")
        }
        File(javaDir, "pkg/Actual.java").apply {
            parentFile.mkdirs()
            writeText("package pkg;")
        }

        val dirs = listOf(
            ExternalBuildGeneratedSourceDir(kotlinDir, ExternalBuildGeneratedLanguage.Kotlin),
            ExternalBuildGeneratedSourceDir(javaDir, ExternalBuildGeneratedLanguage.Java),
        )
        val result = collectGeneratedSourceOutputs(module, dirs, GeneratedSourceSnapshot())

        assertNull(result.error)
        assertEquals(
            setOf(CompileOutput.Type.Kotlin, CompileOutput.Type.Java),
            result.outputs.map { it.type }.toSet(),
        )
        assertEquals(setOf("Expect.kt", "Actual.java"), result.outputs.map { it.file.name }.toSet())
        assertTrue(result.outputs.none { it.file.extension == "cc" })
    }

    @Test
    fun `fails when a declared generated source directory is missing`() {
        val module = ModuleInfo.virtualModule.copy(name = "app")
        val missing = File(temporaryFolder.root, "missing-generated")

        val result = collectGeneratedSourceOutputs(
            module,
            listOf(ExternalBuildGeneratedSourceDir(missing, ExternalBuildGeneratedLanguage.Kotlin)),
            GeneratedSourceSnapshot(),
        )

        assertTrue(result.error?.contains(missing.path) == true)
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun `treats an empty generated source directory as success`() {
        val module = ModuleInfo.virtualModule.copy(name = "app")
        val empty = temporaryFolder.newFolder("empty-generated")

        val result = collectGeneratedSourceOutputs(
            module,
            listOf(ExternalBuildGeneratedSourceDir(empty, ExternalBuildGeneratedLanguage.Kotlin)),
            GeneratedSourceSnapshot(),
        )

        assertNull(result.error)
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun `skips generated sources whose size and timestamp did not change`() {
        val module = ModuleInfo.virtualModule.copy(name = "app")
        val kotlinDir = temporaryFolder.newFolder("generated-kotlin-changed")
        val unchanged = File(kotlinDir, "pkg/Unchanged.kt").apply {
            parentFile.mkdirs()
            writeText("package pkg")
        }
        val rewritten = File(kotlinDir, "pkg/Rewritten.kt").apply {
            parentFile.mkdirs()
            writeText("package pkg; class A")
        }
        val dirs = listOf(ExternalBuildGeneratedSourceDir(kotlinDir, ExternalBuildGeneratedLanguage.Kotlin))
        val snapshot = snapshotGeneratedSourceDirs(dirs)

        rewritten.writeText("package pkg; class BB")
        val created = File(kotlinDir, "pkg/Created.kt").apply {
            writeText("package pkg; class C")
        }

        val result = collectGeneratedSourceOutputs(module, dirs, snapshot)

        assertNull(result.error)
        assertEquals(3, result.scannedCount)
        assertEquals(setOf(rewritten, created), result.outputs.map { it.file }.toSet())
        assertTrue(result.outputs.none { it.file == unchanged })
    }

    private fun cppBuildInfo(root: File) = ExternalBuildInfo(
        type = ExternalBuildType.Cpp,
        inputDirs = listOf(
            ExternalBuildInputDir(
                root,
                setOf(ExternalBuildInputFilterRule.CppSource, ExternalBuildInputFilterRule.CppHeader),
            ),
        ),
        taskPath = ":app:mergeDebugNativeLibs",
        assetsOutputDir = null,
        nativeOutput = File(root, "build/merged"),
        prerequisites = listOf(
            ExternalBuildPrerequisite(
                taskPath = ":app:compileMidl",
                triggerGlobs = listOf("**/*.idl.hpp"),
                generatedSourceDirs = listOf(
                    ExternalBuildGeneratedSourceDir(
                        File(root, "build/generated/idl/kotlin/commonMain"),
                        ExternalBuildGeneratedLanguage.Kotlin,
                    ),
                ),
            ),
        ),
    )
}
