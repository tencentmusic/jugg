package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.obfuscation.InlineEffectedClass
import com.sickworm.intellij.jugg.compiler.obfuscation.MinifyInfo
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that MinifyInfo.inlineEffectedClasses is filtered to only include
 * classes that have corresponding entries in classFiles.
 *
 * This prevents boot classpath classes (e.g., java/lang/Object) from being
 * included in redirectClassMap when they were never in the APK and have no
 * .class file available for generating _jugg_fix DEX.
 */
class CompileEffectAnalyzerMinifyFilterTest {

    @Test
    fun `MinifyInfo should filter inlineEffectedClasses to only classes with classFiles`() {
        // Setup: one class has a .class file, one (boot classpath) does not
        val existingClassFile = File.createTempFile("MinifyTestEnum", ".class")
        existingClassFile.deleteOnExit()

        val allEffectedClasses = listOf(
            InlineEffectedClass(
                className = "Lcom/example/MyClass;",
                effectedByClasses = listOf("Lcom/example/Caller;")
            ),
            InlineEffectedClass(
                className = "Ljava/lang/Object;",
                effectedByClasses = listOf("Lcom/example/Caller;")
            ),
            InlineEffectedClass(
                className = "Landroid/view/View;",
                effectedByClasses = listOf("Lcom/example/Caller;")
            ),
        )

        // Only MyClass has a .class file
        val classFiles = mapOf(
            "com.example.MyClass" to existingClassFile
        )

        val minifyInfo = MinifyInfo(
            inlineEffectedClasses = allEffectedClasses,
            classFiles = classFiles,
        )

        // Verify: effectiveInlineEffectedClasses should only contain classes with classFiles
        val effectiveClasses = minifyInfo.effectiveInlineEffectedClasses
        assertEquals(1, effectiveClasses.size, "Only classes with .class files should be included")
        assertEquals("Lcom/example/MyClass;", effectiveClasses[0].className)
    }

    @Test
    fun `MinifyInfo effectiveInlineEffectedClasses should be empty when no classFiles match`() {
        val allEffectedClasses = listOf(
            InlineEffectedClass(
                className = "Ljava/lang/String;",
                effectedByClasses = listOf("Lcom/example/Caller;")
            ),
        )

        val minifyInfo = MinifyInfo(
            inlineEffectedClasses = allEffectedClasses,
            classFiles = emptyMap(),
        )

        assertTrue(
            minifyInfo.effectiveInlineEffectedClasses.isEmpty(),
            "effectiveInlineEffectedClasses should be empty when no classFiles exist"
        )
    }

    @Test
    fun `MinifyInfo effectiveInlineEffectedClasses should include all when all have classFiles`() {
        val classFile1 = File.createTempFile("ClassA", ".class")
        val classFile2 = File.createTempFile("ClassB", ".class")
        classFile1.deleteOnExit()
        classFile2.deleteOnExit()

        val allEffectedClasses = listOf(
            InlineEffectedClass(
                className = "Lcom/example/ClassA;",
                effectedByClasses = listOf("Lcom/example/Caller;")
            ),
            InlineEffectedClass(
                className = "Lcom/example/ClassB;",
                effectedByClasses = listOf("Lcom/example/Caller;")
            ),
        )

        val classFiles = mapOf(
            "com.example.ClassA" to classFile1,
            "com.example.ClassB" to classFile2,
        )

        val minifyInfo = MinifyInfo(
            inlineEffectedClasses = allEffectedClasses,
            classFiles = classFiles,
        )

        assertEquals(
            2,
            minifyInfo.effectiveInlineEffectedClasses.size,
            "All classes should be included when all have classFiles"
        )
    }
}
