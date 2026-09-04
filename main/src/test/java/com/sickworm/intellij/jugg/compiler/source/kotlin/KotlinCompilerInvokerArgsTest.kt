package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** L1 tests for deterministic Kotlin compiler argument assembly. */
class KotlinCompilerInvokerArgsTest {

    @Test
    fun `adds multiplatform arguments for explicit common sources`() {
        val common = listOf(File("/tmp/Res.kt"), File("/tmp/String0.commonMain.kt"))
        val commonPaths = common.joinToString(",") { it.absolutePath }

        assertEquals(
            listOf("-Xmulti-platform", "-Xcommon-sources=$commonPaths"),
            KotlinCompilerInvoker.buildCommonSourceArgs(common),
        )
    }

    @Test
    fun `does not add multiplatform arguments without common sources`() {
        assertTrue(KotlinCompilerInvoker.buildCommonSourceArgs(emptyList()).isEmpty())
    }

    @Test
    fun `deduplicates common sources without changing their order`() {
        val first = File("/tmp/Common.kt")
        val second = File("/tmp/Shared.kt")

        assertEquals(
            listOf(
                "-Xmulti-platform",
                "-Xcommon-sources=${first.absolutePath},${second.absolutePath}",
            ),
            KotlinCompilerInvoker.buildCommonSourceArgs(listOf(first, second, first)),
        )
    }

    @Test
    fun `does not duplicate compose options provided by project`() {
        val projectArgs = listOf(
            "-P",
            "plugin:androidx.compose.plugins.idea:enabled=true",
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true",
        )

        assertTrue(KotlinCompilerInvoker.buildMissingComposeOptions(projectArgs).isEmpty())
    }

    @Test
    fun `adds default compose options when project does not provide them`() {
        assertEquals(
            listOf(
                "-P",
                "plugin:androidx.compose.plugins.idea:enabled=true",
                "-P",
                "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true",
            ),
            KotlinCompilerInvoker.buildMissingComposeOptions(emptyList()),
        )
    }

    @Test
    fun `passes Gradle compiler plugin options through Kotlin CLI protocol`() {
        assertEquals(
            listOf(
                "-P",
                "plugin:dev.zacsweers.moshix.compiler:enabled=true",
                "-P",
                "plugin:dev.zacsweers.moshix.compiler:enableSealed=true",
            ),
            KotlinCompilerInvoker.buildPluginOptionArgs(
                listOf(
                    "plugin:dev.zacsweers.moshix.compiler:enabled=true",
                    "plugin:dev.zacsweers.moshix.compiler:enableSealed=true",
                ),
            ),
        )
    }

    @Test
    fun `uses current compilation plugin options instead of merging parent options`() {
        val current = ModuleInfo.virtualModule.copy(
            name = "app.androidMain",
            kotlinPluginOptions = listOf("plugin:sample.current:enabled=true"),
        )
        val parent = ModuleInfo.virtualModule.copy(
            name = "app",
            kotlinPluginOptions = listOf("plugin:sample.parent:enabled=true"),
        )

        assertEquals(
            current.kotlinPluginOptions,
            KotlinCompiler.selectKotlinPluginOptions(listOf(current, parent)),
        )
    }

    @Test
    fun `falls back to nearest parent plugin options without removing repeated options`() {
        val repeatedOptions = listOf(
            "plugin:org.jetbrains.kotlin.allopen:annotation=sample.First",
            "plugin:org.jetbrains.kotlin.allopen:annotation=sample.Second",
        )
        val current = ModuleInfo.virtualModule.copy(name = "app.androidMain")
        val parent = ModuleInfo.virtualModule.copy(name = "app", kotlinPluginOptions = repeatedOptions)

        assertEquals(
            repeatedOptions,
            KotlinCompiler.selectKotlinPluginOptions(listOf(current, parent)),
        )
    }

    @Test
    fun `does not pass project plugin options when project plugins are not loaded`() {
        assertTrue(
            KotlinCompilerInvoker.buildPluginOptionArgs(
                listOf("plugin:dev.zacsweers.moshix.compiler:enabled=true"),
                isProjectPluginEnabled = false,
            ).isEmpty(),
        )
    }

    @Test
    fun `removes only resolved options for unsupported plugin`() {
        val options = listOf(
            "plugin:dev.zacsweers.moshix.compiler:enabled=true",
            "plugin:dev.zacsweers.moshix.compiler:newOption=true",
            "plugin:sample.other:enabled=true",
        )
        val messages = listOf(
            "error: unsupported plugin option: dev.zacsweers.moshix.compiler:newOption=true",
        )

        val pluginId = KotlinCompilerInvoker.findUnsupportedProjectPluginId(messages, options)

        assertEquals("dev.zacsweers.moshix.compiler", pluginId)
        assertEquals(
            listOf("plugin:sample.other:enabled=true"),
            KotlinCompilerInvoker.removePluginOptions(options, pluginId!!),
        )
    }

    @Test
    fun `does not downgrade unsupported option outside resolved plugin options`() {
        assertEquals(
            null,
            KotlinCompilerInvoker.findUnsupportedProjectPluginId(
                listOf("error: unsupported plugin option: sample.manual:enabled=true"),
                listOf("plugin:sample.resolved:enabled=true"),
            ),
        )
    }

    @Test
    fun `compiler toolchain key isolates different project compiler classpaths`() {
        val oldCompiler = listOf(File("/tmp/kotlin-compiler-1.6.21.jar"), File("/tmp/kotlin-stdlib.jar"))
        val sameCompiler = listOf(
            File("/tmp/cache/../kotlin-compiler-1.6.21.jar"),
            File("/tmp/kotlin-stdlib.jar"),
        )
        val newCompiler = listOf(File("/tmp/kotlin-compiler-2.2.10.jar"), File("/tmp/kotlin-stdlib.jar"))

        assertEquals(
            KotlinCompilerInvoker.buildCompilerToolchainKey(oldCompiler),
            KotlinCompilerInvoker.buildCompilerToolchainKey(sameCompiler),
        )
        assertNotEquals(
            KotlinCompilerInvoker.buildCompilerToolchainKey(oldCompiler),
            KotlinCompilerInvoker.buildCompilerToolchainKey(newCompiler),
        )
    }

    @Test
    fun `finds compiler plugin by declared id instead of artifact name`() {
        val jar = File.createTempFile("moshi-ir-compiler-plugin", ".jar")
        try {
            JarOutputStream(jar.outputStream()).use { output ->
                output.putNextEntry(JarEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"))
                output.write("sample.MoshiXCommandLineProcessor".toByteArray())
                output.closeEntry()
                output.putNextEntry(JarEntry("sample/MoshiXCommandLineProcessor.class"))
                val classWriter = ClassWriter(0)
                classWriter.visit(
                    Opcodes.V1_8,
                    Opcodes.ACC_PUBLIC,
                    "sample/MoshiXCommandLineProcessor",
                    null,
                    "java/lang/Object",
                    null,
                )
                classWriter.visitField(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    "PLUGIN_ID",
                    "Ljava/lang/String;",
                    null,
                    "dev.zacsweers.moshix.compiler",
                ).visitEnd()
                classWriter.visitEnd()
                output.write(classWriter.toByteArray())
                output.closeEntry()
            }

            assertEquals(
                listOf(jar),
                KotlinCompilerInvoker.findPluginFilesById(
                    "dev.zacsweers.moshix.compiler",
                    listOf(jar),
                ),
            )
        } finally {
            jar.delete()
        }
    }
}
