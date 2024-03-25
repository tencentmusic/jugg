package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.DexCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File

class DexCompileTest {

    private val dexCompiler = DexCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
    }

    private val classTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Class,
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/com/example/myapplication/MainActivity2.class"),
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/"),
                mockModule,
            ),
            CompileFile(
                CompileFile.Type.Class,
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/com/example/myapplication/ABC.class"),
                File(assetsAndroidDir, "app/build/intermediates/javac/debug/classes/"),
                mockModule,
            )
        ),
        stagingDir,
    )

    @Test
    fun compileClasses() {
        val task = classTask
        val result = dexCompiler.compile(task)
        assertCompileResult(task, result)
    }

    private val jarsTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Class,
                File(assetsLibDir, "reactive-streams-1.0.3.jar"),
                assetsLibDir,
                mockModule,
            ).withJarDexFileName("#org.reactivestreams#reactive-streams.dex"),
            CompileFile(
                CompileFile.Type.Class,
                File(assetsLibDir, "rxjava-3.0.12.jar"),
                assetsLibDir,
                mockModule,
            ).withJarDexFileName("#io.reactivex.rxjava3#rxjava.dex")
        ),
        stagingDir,
    )

    @Test
    fun compileJars() {
        val task = jarsTask
        val result = dexCompiler.compile(task)
        assertCompileResult(task, result)
    }

    @Test
    fun compileClassesAndJars() {
        val task = jarsTask + classTask
        val result = dexCompiler.compile(task)
        assertCompileResult(task, result)
    }

    private fun assertCompileResult(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = { compileFile ->
            if (compileFile.file.extension == "class") {
                listOf(
                    CompileOutput(
                        CompileOutput.Type.Dex,
                        compileFile.file.changeBaseDir(compileFile.baseDir, stagingDir, "dex"),
                        stagingDir,
                    )
                )
            } else {
                listOf(
                    CompileOutput(
                        CompileOutput.Type.Dex,
                        File(stagingDir, compileFile.jarDexFileName),
                        stagingDir,
                    )
                )
            }
        }
        assertCompileResult(task, result, mapper)
    }
}