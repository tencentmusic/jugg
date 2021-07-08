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

    private val helloWorldTask = CompileTask.singleJavaFile(
        filePath = "$assetsJavaDir/com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java",
        outputDir = compileDexDir)
    @Test
    fun compileJavaDex() {
        val result = aidpCompiler.compile(helloWorldTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), true)
        assert(result.outputs.size == 1)

        result.outputs.forEach {
            assert(it.type == CompileOutput.Type.Dex)
            assert(it.file.exists() && it.file.length() > 0)
        }
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
            CompileFile(it, CompileFile.Type.Java, File(assetsJavaDir),
                dependencyPaths = dependencies)
        },
        outputDir = File(compileDexDir))
    @Test
    fun compileMultiJavaDex() {
        val result = aidpCompiler.compile(multiTask)
        assert(result.details.size == multiTask.files.size)
        result.details.forEach {
            assertCompileResult(assetsJavaDir, it, true)
        }
        result.outputs.forEach {
            assert(it.type == CompileOutput.Type.Dex)
            assert(it.file.exists() && it.file.length() > 0)
        }
    }

    private val multiWithErrorTask = CompileTask(
        javaCompileFilesWithError.map {
            CompileFile(it, CompileFile.Type.Java, File(assetsJavaDir),
                dependencyPaths = dependencies)
        },
        outputDir = File(compileDexDir))
    @Test
    fun compileMultiJavaWithErrorDex() {
        val result = aidpCompiler.compile(multiWithErrorTask)
        assert(result.details.size == multiWithErrorTask.files.size)
        result.details.forEach {
            if (it.file.file.name == "ErrorJavaFile.java") {
                assertCompileResult(assetsJavaDir, it, false, 2)
            } else {
                assertCompileResult(assetsJavaDir, it, false, 0)
            }
        }
        assert(result.outputs.isEmpty())
    }
}