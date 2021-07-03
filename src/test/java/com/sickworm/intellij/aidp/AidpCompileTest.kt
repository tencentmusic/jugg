package com.sickworm.intellij.aidp

import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import org.junit.Before
import org.junit.Test
import java.io.File

class AidpCompileTest {

    private val disposable = Disposable { }
    private val project = MockProject(null, disposable)
    private val aidpCompiler = AidpCompiler(project, File(compileClassDir), File(classPathDir))

    @Before
    fun init() {
        clearBuild()
    }

    private val helloWorldTask = CompileTask.singleFile(
        filePath = "$assetsJavaDir/com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java",
        outputDir = compileDexDir)
    @Test
    fun compileJavaDex() {
        val results = aidpCompiler.compile(helloWorldTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), true, isCheckDexExist = true)
    }

    private val javaCompileFilesWithError = File(assetsJavaDir).listFilesRecursively()
    private val javaCompileFiles = javaCompileFilesWithError.filter { it.name != "ErrorJavaFile.java" }
    private val dependencies: List<String> = emptyList<String>() +
            "$assetsLibDir/rxjava-3.0.12.jar" +
            "$assetsLibDir/reactive-streams-1.0.3.jar" +
            androidJar +
            "$assetsAndroidDir/build/intermediates/javac/debug/classes" +
            assetsClassDir +
            IntellijLibraryConfigParser(File(intellijLibraryDir)).parse()!!

    private val multiTask = CompileTask(
        javaCompileFiles.map {
            CompileFileInfo(it,
                dependencyPaths = dependencies)
        },
        outputDir = File(compileDexDir))
    @Test
    fun compileMultiJavaDex() {
        val results = aidpCompiler.compile(multiTask)
        assert(results.size == multiTask.files.size)
        results.forEach {
            assertCompileResult(assetsJavaDir, it, true, isCheckDexExist = true)
        }
    }

    private val multiWithErrorTask = CompileTask(
        javaCompileFilesWithError.map {
            CompileFileInfo(it,
                dependencyPaths = dependencies)
        },
        outputDir = File(compileDexDir))
    @Test
    fun compileMultiJavaWithErrorDex() {
        val results = aidpCompiler.compile(multiWithErrorTask)
        assert(results.size == multiWithErrorTask.files.size)
        results.forEach {
            if (it.file.file.name == "ErrorJavaFile.java") {
                assertCompileResult(assetsJavaDir, it, false, 2)
            } else {
                assertCompileResult(assetsJavaDir, it, false, 0)
            }
        }
    }
}