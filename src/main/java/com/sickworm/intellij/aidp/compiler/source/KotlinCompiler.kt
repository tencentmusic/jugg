package com.sickworm.intellij.aidp.compiler.source

import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.listFilesRecursively
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.*

class KotlinCompiler(context: ICompileContext): BaseCompiler(context) {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = true

    private val kotlinCompile = K2JVMCompiler()

    override fun doCompile(task: CompileTask): CompileResult {
        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()

        // TODO read from project
        val packageName = "com.tencent.wesing.camerasource.example"
        val flavor = "main"
        val resourcePath = "/Users/wormchen/IdeaProjects/TMEVideoRecord/app/src/main/res"
        val command = mutableListOf<String>(
            // TODO read from classpath
            "-Xplugin=/Users/wormchen/IdeaProjects/android-incremental-deploy-plugin/src/main/resources/kotlin_compile/kotlin-android-extensions-1.4.32.jar",
            "-P", "plugin:org.jetbrains.kotlin.android:package=${packageName}",
            "-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}",
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-d", task.outputDir.absolutePath,
        )
        if (dependencies.isNotEmpty()) {
            command.add("-cp")
            command.add(dependencies.joinToString(File.pathSeparator))
        }
        command.add(task.files.joinToString(separator = " ") { it.file.absolutePath })
        kotlinCompile.exec(printStream, *command.toTypedArray())
        logger.debug("compile: ${String(outputStream.toByteArray())}")

        // TODO check error
        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}
