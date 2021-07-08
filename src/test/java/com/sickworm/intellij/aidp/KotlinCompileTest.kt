package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File

class KotlinCompileTest {

    private val kotlinCompiler = KotlinCompiler()

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun cliCompile() {
        val file = "$assetsKotlinDir/com/sickworm/intellij/aidp/test/Result.kt"
        assert(File(file).exists())
        val command = "D:\\Java\\jdk1.8.0_77\\bin\\java.exe -Xmx256M -Xms32M -noverify -cp D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-preloader.jar     org.jetbrains.kotlin.preloading.Preloader -cp D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-compiler.jar      org.jetbrains.kotlin.cli.jvm.K2JVMCompiler  ${File(file).absolutePath} -d $classPathDir"
        Runtime.getRuntime().exec(command)
    }

    private val resultTask = CompileTask(
        listOf(CompileFile(File("$assetsKotlinDir/com/sickworm/intellij/aidp/test/Result.kt"),
            CompileFile.Type.Kotlin,
            File(assetsJavaDir))),
        File(classPathDir))
    @Test
    fun kotlinCompile() {
        val result = kotlinCompiler.compile(resultTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsKotlinDir, result.details.first(), true)
    }
}