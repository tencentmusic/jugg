package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerProcessRunner
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Gives the official processor javac access without changing the IDE JVM or normal compilation. */
internal object ButterKnifeJavaApt {
    fun generate(context: ICompileContext, module: ModuleInfo, sources: List<File>, processors: List<File>,
        options: Map<String, String>, work: File): File {
        check(!work.exists() || work.deleteRecursively()) { "Cannot clear $work" }
        val generated = File(work, "sources").apply { mkdirs() }
        val classes = File(work, "classes").apply { mkdirs() }
        val task = CompileTask(emptyList(), work, CompileStatusHolder.DEFAULT)
        val dependencies = context.getModuleDependencies(module, task) + processors.map { it.path }
        val javaHome = KotlinCompilerProcessRunner.resolveJavaHome(context.cmdCompileEnv, System.getProperty("java.home"))
        val javac = File(javaHome, if (isWindows) "bin/javac.exe" else "bin/javac")
        check(javac.isFile) { "ButterKnife requires a JDK with javac at $javaHome" }
        val version = File(javaHome, "release").takeIf { it.isFile }?.readText().orEmpty()
        val exports = if (version.contains("JAVA_VERSION=\"1.8.")) emptyList() else listOf("code", "tree", "processing", "util").flatMap { pkg ->
            listOf("-J--add-exports=jdk.compiler/com.sun.tools.javac.$pkg=ALL-UNNAMED",
                "-J--add-opens=jdk.compiler/com.sun.tools.javac.$pkg=ALL-UNNAMED")
        }
        val arguments = listOf("-proc:only", "-Xlint:-options", "-encoding", "UTF-8", "-source", module.javaSourceCompatibility ?: "8",
            "-target", module.javaTargetCompatibility ?: "8", "-classpath", dependencies.joinToString(File.pathSeparator),
            "-processorpath", processors.joinToString(File.pathSeparator), "-processor", ButterKnifeJuggAptProcessor.PROCESSOR,
            "-s", generated.path, "-d", classes.path) + options.map { "-A${it.key}=${it.value}" } + sources.map { it.path }
        val argsFile = File(work, "javac.args").apply {
            writeText(arguments.joinToString("\n") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" })
        }
        val output = mutableListOf<String>()
        val exit = CmdExecutor(context.logger, isLogAllDebug = true).invokeProcess(
            listOf(javac.path) + exports + "@${argsFile.path}", outputCollector = output)
        if (exit != 0) {
            context.logger.warn("ButterKnife APT failed for ${module.name}: ${output.joinToString("\n")}")
            error("ButterKnife APT exited with $exit; see compiler diagnostics")
        }
        return generated
    }
}
