package com.sickworm.intellij.jugg.compiler.source.kotlin

import org.junit.Assert.assertEquals
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
}
