package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.JavaCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File

class JavaCompileTest {

    private val javaCompiler = JavaCompiler(context)

    @Before
    fun init() {
        clearBuild()
    }

    val helloWorldTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/jugg/test/HelloWorldJavaFile.java"), stagingDir)
    @Test
    fun javaCompile() {
        val task = helloWorldTask
        val result = javaCompiler.compile(task)
        assertCompileResultJava(task, result)
    }

    val errorTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/jugg/test/ErrorJavaFile.java"), stagingDir)
    @Test
    fun javaCompileError() {
        val task = errorTask
        val result = javaCompiler.compile(task)
        assertCompileResultFailed(task, result, mapOf(errorTask.files[0] to 2))
    }

    val externalDepTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/jugg/test/JavaFileWithExternalDep.java"),
        stagingDir,
        dependencies = listOf(
            "$assetsLibDir/rxjava-3.0.12.jar",
            "$assetsLibDir/reactive-streams-1.0.3.jar"
        )
    )
    @Test
    fun javaCompileWithExternalDep() {
        val task = externalDepTask
        val result = javaCompiler.compile(task)
        assertCompileResultJava(task, result)
    }

    val classDepTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/jugg/test/JavaFileWithClassDep.java"),
        stagingDir,
        dependencies = listOf(assetsClassDir.absolutePath)
    )
    @Test
    fun javaCompileWithClassDep() {
        val task = classDepTask
        val result = javaCompiler.compile(task)
        assertCompileResultJava(task, result)
    }

    val activityTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/example/myapplication/MainActivity2.java"),
        stagingDir,
        dependencies = listOf(androidJar.absolutePath)
                + "$assetsAndroidDir/app/build/intermediates/javac/debug/classes"
                + IntellijLibraryConfigParserTest().loadLibraryConfigInTest()!!
    )
    @Test
    fun javaCompileAndroidActivity() {
        val task = activityTask
        val result = javaCompiler.compile(task)
        assertCompileResultJava(task, result)
    }

    val interdependenceTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Java, File(assetsJavaDir, "com/sickworm/intellij/jugg/test/JavaFileWithInterdependence.java"), assetsJavaDir, mockModule),
            CompileFile(CompileFile.Type.Java, File(assetsJavaDir, "com/sickworm/intellij/jugg/test/NewDep.java"), assetsJavaDir, mockModule)
        ),
        stagingDir
    )

    @Test
    fun javaCompileMultiFilesWithDep() {
        val task = interdependenceTask
        val result = javaCompiler.compile(task)
        assertCompileResultJava(task, result)
    }

    val multiFilesTask = helloWorldTask + externalDepTask + classDepTask + activityTask + interdependenceTask
    @Test
    fun javaCompileMultiFiles() {
        val task = multiFilesTask
        val result = javaCompiler.compile(task)
        assertCompileResultJava(task, result)
    }

    val multiFilesWithErrorTask = helloWorldTask + errorTask + externalDepTask + classDepTask + activityTask + interdependenceTask
    @Test
    fun javaCompileMultiFilesError() {
        val task = multiFilesWithErrorTask
        val result = javaCompiler.compile(task)
        assertCompileResultFailed(task, result, mapOf(errorTask.files[0] to 2))
    }

    private fun assertCompileResultJava(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "class")
            listOf(CompileOutput(CompileOutput.Type.Class, outputFile, task.outputDir))
        }
        assertCompileResult(task, result, mapper)
    }
}