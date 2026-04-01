package com.sickworm.intellij.jugg.compiler.obfuscation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class R8UsageReaderTest {

    @Test
    fun `fromString should parse removed classes and methods`() {
        val usageContent = """
            # compiler: R8
            com.example.RemovedClass
            com.example.Target:
                public void <init>()
                public final void keep(java.lang.String,int[])
                public final void call(com.example.Outer${'$'}Inner,java.lang.String[],int[][])
                public static final int FLAG
        """.trimIndent()

        val reader = R8UsageReader.fromString(usageContent)

        assertTrue(reader.isClassRemoved("com.example.RemovedClass"))
        assertFalse(reader.isClassRemoved("com.example.Target"))

        assertTrue(reader.isMethodRemoved("com.example.Target", "<init>", emptyList()))
        assertTrue(reader.isMethodRemoved("com.example.Target", "keep", listOf("java.lang.String", "int[]")))
        assertTrue(
            reader.isMethodRemoved(
                "com.example.Target",
                "call",
                listOf("com.example.Outer${'$'}Inner", "java.lang.String[]", "int[][]")
            )
        )
        assertFalse(reader.isMethodRemoved("com.example.Target", "call", listOf("java.lang.String[]")))
        assertEquals(3, reader.getRemovedMethods("com.example.Target").size)
    }

    @Test
    fun `fromString should ignore comments invalid lines and orphan members`() {
        val usageContent = """
            # comment
                public void orphanMethod()
            not a valid line -> still ignored
            com.example.ValidTarget:
                public final java.lang.String validMethod()
        """.trimIndent()

        val reader = R8UsageReader.fromString(usageContent)

        assertFalse(reader.isClassRemoved("not a valid line -> still ignored"))
        assertFalse(reader.isMethodRemoved("com.example.ValidTarget", "orphanMethod", emptyList()))
        assertTrue(reader.isMethodRemoved("com.example.ValidTarget", "validMethod", emptyList()))
    }
}
