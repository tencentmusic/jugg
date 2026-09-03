package com.sickworm.intellij.jugg.compiler.source.kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
