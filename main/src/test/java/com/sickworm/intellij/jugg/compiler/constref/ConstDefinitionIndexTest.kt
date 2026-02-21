package com.sickworm.intellij.jugg.compiler.constref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstDefinitionIndexTest {
    @Test
    fun `should replace file definitions incrementally`() {
        val sourcePath = "/tmp/Constants.kt"
        val index = ConstDefinitionIndex(
            listOf(
                definition(
                    filePath = sourcePath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MAX",
                ),
                definition(
                    filePath = "/tmp/Other.kt",
                    packageName = "com.example",
                    fqClassName = "com.example.OtherKt",
                    constName = "OTHER",
                ),
            )
        )

        assertTrue(index.hasDefinition("com.example.ConstantsKt", "MAX"))
        assertTrue(index.hasConstName("MAX"))

        index.replaceFileDefinitions(
            filePath = sourcePath,
            definitions = listOf(
                definition(
                    filePath = sourcePath,
                    packageName = "com.example",
                    fqClassName = "com.example.ConstantsKt",
                    constName = "MIN",
                )
            ),
        )

        assertFalse(index.hasDefinition("com.example.ConstantsKt", "MAX"))
        assertFalse(index.hasConstName("MAX"))
        assertTrue(index.hasDefinition("com.example.ConstantsKt", "MIN"))
        assertEquals(setOf("com.example.ConstantsKt"), index.findClassBySimpleName("ConstantsKt"))
    }

    @Test
    fun `should keep class index until last duplicated definition removed`() {
        val className = "com.example.Constants"
        val fileA = "/tmp/debug/Constants.java"
        val fileB = "/tmp/release/Constants.java"
        val index = ConstDefinitionIndex(
            listOf(
                definition(
                    filePath = fileA,
                    packageName = "com.example",
                    fqClassName = className,
                    constName = "MAX",
                ),
                definition(
                    filePath = fileB,
                    packageName = "com.example",
                    fqClassName = className,
                    constName = "MAX",
                ),
            )
        )

        assertEquals(2, index.findByClassAndConst(className, "MAX").size)
        assertTrue(index.hasClass(className))

        index.removeFileDefinitions(fileA)
        assertEquals(1, index.findByClassAndConst(className, "MAX").size)
        assertTrue(index.hasClass(className))

        index.removeFileDefinitions(fileB)
        assertTrue(index.findByClassAndConst(className, "MAX").isEmpty())
        assertFalse(index.hasClass(className))
        assertTrue(index.findClassBySimpleName("Constants").isEmpty())
    }

    private fun definition(
        filePath: String,
        packageName: String,
        fqClassName: String,
        constName: String,
    ): ConstDefinition {
        return ConstDefinition(
            filePath = filePath,
            packageName = packageName,
            fqClassName = fqClassName,
            constName = constName,
            constType = "Int",
            constValue = "1",
        )
    }
}
