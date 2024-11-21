package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.overlay.RJavaFixer
import com.sickworm.intellij.jugg.compiler.source.JavaCompiler
import com.sickworm.intellij.jugg.mock.*
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse

class RFileFixerTest {

    @Test
    fun testBigRJava() {
        buildDir.clearDir()

        val rFile = File(assetsDir, "java/com/example/myapplication/R.java")
        assertTrue(rFile.exists())

        val baseDir = File(buildDir, "java")
        val tmpRFile = File(baseDir, "com/example/myapplication/R.java")
        rFile.copyTo(tmpRFile, overwrite = true)

        val javaCompiler = JavaCompiler(context, mockParentDisposable)
        val task = com.sickworm.intellij.jugg.compiler.CompileTask.singleJavaFile(tmpRFile, File(buildDir, "output"), baseDir)
        var result = javaCompiler.compile(task)
        assertFalse(result.isAllSuccess)

        RJavaFixer(logger).fixIfNeeded(tmpRFile)
        assertTrue(tmpRFile.readText().contains("public static class id1"))
        assertTrue(tmpRFile.readText().contains("public static final class id extends id1 {"))

        File(buildDir, "output").clearDir()
        result = javaCompiler.compile(task)
        assertTrue(result.isAllSuccess)
        assertTrue(result.outputs.isNotEmpty())
    }

}