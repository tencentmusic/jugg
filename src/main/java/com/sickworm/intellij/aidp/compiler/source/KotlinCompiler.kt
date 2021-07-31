package com.sickworm.intellij.aidp.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.Result
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.isWindows
import com.sickworm.intellij.aidp.listFilesRecursively
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class KotlinCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    val kotlinCompiler = K2JVMCompiler()

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)
        task.outputDir.mkdirs()

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        // TODO read from environment
        val javaCmd = if (isWindows) "D:/Java/jdk1.8.0_77/bin/java.exe" else "java"
        val kotlincLibDir = if (isWindows) "D:/JETBRA~1/INTELL~1.2/plugins/Kotlin/kotlinc/lib"
        else "/Users/wormchen/IdeaProjects/studio-master-dev/prebuilts/tools/common/kotlin-plugin/Kotlin/kotlinc/lib"
        val preloader = "$kotlincLibDir/kotlin-preloader.jar org.jetbrains.kotlin.preloading.Preloader"
        val compiler = "$kotlincLibDir/kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        val dependenciesArg = if (dependencies.isEmpty()) "" else "-cp " + dependencies.joinToString(File.pathSeparator)
        val jvmVersionArg = "-jvm-target 1.8"
        val outputArg = "-d ${task.outputDir}"
//        val command = "$javaCmd -Xmx256M -Xms32M -noverify -cp $preloader -cp $compiler ${task.files[0].file.absolutePath} $jvmVersionArg $dependenciesArg $outputArg"
        val command = mutableListOf<String>(
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-d", task.outputDir.absolutePath
        )
        if (dependencies.isNotEmpty()) {
            command.add("-cp")
            command.add(dependencies.joinToString(File.pathSeparator))
        }
        command.add(task.files.joinToString(separator = " ") { it.file.absolutePath })
        println(command)
        kotlinCompiler.exec(printStream, *command.toTypedArray())
        logger.warn("compile: ${String(outputStream.toByteArray())}")
//        println(command)
//        val pr = Runtime.getRuntime().exec(command)
//        pr.readOutput(logger)
//        pr.waitFor()

        // TODO check error
        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}