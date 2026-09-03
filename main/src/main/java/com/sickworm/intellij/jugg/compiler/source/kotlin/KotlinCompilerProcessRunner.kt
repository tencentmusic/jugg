package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.isWindows
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the project Kotlin compiler in a child JVM so it does not inherit incompatible IDE
 * process state, while retaining the module exports required by KAPT.
 */
internal class KotlinCompilerProcessRunner(private val logger: Logger) {

    fun exec(
        compileEnv: List<String>,
        projectDir: File,
        task: CompileTask,
        compilerClasspath: List<File>,
        compilerArgs: List<String>,
        outputStream: PrintStream,
    ): ExitCode {
        val javaHome = resolveJavaHome(compileEnv, System.getProperty("java.home"))
        val javaFeature = readJavaFeature(javaHome)
        val command = buildCommand(javaHome, javaFeature, compilerClasspath, compilerArgs)
        logger.debug("isolated Kotlin compiler java: ${command.first()}, feature: $javaFeature")

        val processBuilder = ProcessBuilder(command)
            .directory(projectDir)
            .redirectErrorStream(true)
        compileEnv.forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) {
                processBuilder.environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
        }
        val process = processBuilder.start()
        val outputThread = thread(name = "jugg-kotlin-compiler-output", isDaemon = true) {
            process.inputStream.use { it.copyTo(outputStream) }
        }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(PROCESS_TIMEOUT_MINUTES)
        while (!process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (task.isShouldCancel) {
                logger.debug("isolated Kotlin compiler canceled")
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                outputThread.join()
                return ExitCode.COMPILATION_ERROR
            }
            if (System.nanoTime() >= deadline) {
                logger.warn("Isolated Kotlin compiler timed out after $PROCESS_TIMEOUT_MINUTES minutes")
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                outputThread.join()
                return ExitCode.COMPILATION_ERROR
            }
        }
        outputThread.join()
        outputStream.flush()
        return when (process.exitValue()) {
            0 -> ExitCode.OK
            1 -> ExitCode.COMPILATION_ERROR
            2 -> ExitCode.INTERNAL_ERROR
            3 -> ExitCode.SCRIPT_EXECUTION_ERROR
            else -> ExitCode.INTERNAL_ERROR
        }
    }

    companion object {
        private const val PROCESS_TIMEOUT_MINUTES = 5L
        private const val PROCESS_POLL_MILLIS = 100L
        private const val COMPILER_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        private val JAVAC_PACKAGES = listOf(
            "com.sun.tools.javac.api",
            "com.sun.tools.javac.code",
            "com.sun.tools.javac.comp",
            "com.sun.tools.javac.file",
            "com.sun.tools.javac.jvm",
            "com.sun.tools.javac.main",
            "com.sun.tools.javac.model",
            "com.sun.tools.javac.parser",
            "com.sun.tools.javac.processing",
            "com.sun.tools.javac.tree",
            "com.sun.tools.javac.util",
        )

        internal fun resolveJavaHome(compileEnv: List<String>, systemJavaHome: String): File {
            val configuredJavaHome = compileEnv
                .firstOrNull { it.startsWith("JAVA_HOME=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
            return File(configuredJavaHome ?: systemJavaHome)
        }

        internal fun buildCommand(
            javaHome: File,
            javaFeature: Int,
            compilerClasspath: List<File>,
            compilerArgs: List<String>,
        ): List<String> {
            val javaExecutable = File(javaHome, if (isWindows) "bin/java.exe" else "bin/java")
            val moduleArgs = if (javaFeature >= 9) {
                JAVAC_PACKAGES.flatMap { packageName ->
                    listOf(
                        "--add-exports=jdk.compiler/$packageName=ALL-UNNAMED",
                        "--add-opens=jdk.compiler/$packageName=ALL-UNNAMED",
                    )
                }
            } else {
                emptyList()
            }
            return listOf(
                javaExecutable.path,
                "-Xmx2g",
                "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=true",
            ) + moduleArgs + listOf(
                "-cp",
                compilerClasspath.joinToString(File.pathSeparator) { it.absolutePath },
                COMPILER_MAIN_CLASS,
            ) + compilerArgs
        }

        private fun readJavaFeature(javaHome: File): Int {
            val version = File(javaHome, "release")
                .takeIf { it.isFile }
                ?.useLines { lines ->
                    lines.firstOrNull { it.startsWith("JAVA_VERSION=") }
                }
                ?.substringAfter('=')
                ?.trim('"')
                ?: return Runtime.version().feature()
            val first = version.substringBefore('.')
            return if (first == "1") {
                version.substringAfter('.').substringBefore('.').toIntOrNull() ?: 8
            } else {
                first.toIntOrNull() ?: Runtime.version().feature()
            }
        }
    }
}
