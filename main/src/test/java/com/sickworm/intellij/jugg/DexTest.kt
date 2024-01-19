package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compile.JavaCompileTest
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.source.DexFileMaker
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.mock.androidJar
import com.sickworm.intellij.jugg.mock.clearBuild
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.stagingDir
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class DexTest {

    private val javaCompileTest = JavaCompileTest()

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun dex() {
        javaCompileTest.javaCompile()
        val task = JavaCompileTest().helloWorldTask
        repeat(100) {
            dexAndCheck(task, deleteAfterBuild = true)
        }
    }

    @Test
    fun dexMultipleFiles() {
        JavaCompileTest().javaCompileMultiFiles()
        val task = JavaCompileTest().multiFilesTask
        dexAndCheck(task, deleteAfterBuild = false)
    }

    private fun dexAndCheck(task: CompileTask, deleteAfterBuild: Boolean) {
        val classesFiles = stagingDir.listFilesRecursively()

        // ART TI requires one .dex file only contains one .class file

        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        DexFileMaker(logger).dex(stagingDir, classesFiles, dependencies, androidJar, JuggSettings.minApi)

        classesFiles.forEach { classFile ->
            val dexFile = classFile.changeBaseDir(stagingDir, stagingDir, "dex")
            assertTrue(dexFile.exists() && dexFile.length() > 0)
            if (deleteAfterBuild) {
                dexFile.delete()
            }
        }
    }
}