package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File

class KotlinCompileTest {

    @Before
    fun init() {
        File(buildDir).listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun cliCompile() {
        val file = "$assetsKotlinDir/com/sickworm/intellij/aidp/test/Result.kt"
        assert(File(file).exists())
        val command = "D:\\Java\\jdk1.8.0_77\\bin\\java.exe -Xmx256M -Xms32M -noverify -cp D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-preloader.jar     org.jetbrains.kotlin.preloading.Preloader -cp D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-compiler.jar      org.jetbrains.kotlin.cli.jvm.K2JVMCompiler  ${File(file).absolutePath} -d $buildDir"
        Runtime.getRuntime().exec(command)
    }
}