package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker.Options
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File

/**
 * KspArgsManager carries module, context, and options.
 */
data class KspArgsManager(
    val module: ModuleInfo,
    val context: ICompileContext,
    val options: Options,
) {

    private val kspOutputDir = context.tempCompileDir.resolve("generated/ksp/debug")
    private val kspCachesDir = context.tempCompileDir.resolve("kspCaches/debug")

    private val projectBaseDir = module.moduleRootDir.path
    private val kspClassOutputDir = kspOutputDir.resolve("classes")
    private val kspJavaOutputDir = kspOutputDir.resolve("java")
    private val kspKotlinOutputDir = kspOutputDir.resolve("kotlin")
    private val kspResourceOutputDir = kspOutputDir.resolve("resource")

    // Detect if using KSP2 (Kotlin 2.0+)
    // KSP2 uses symbol-processing-aa-embeddable or version 2.x
    // Check both kspDependencies and kotlinPlugins
    private val isKsp2: Boolean by lazy {
        val allKspJars = options.kspDependencies + options.kotlinPlugins
        allKspJars.any {
            it.name.contains("symbol-processing-aa-embeddable") ||
            it.name.matches(Regex(".*symbol-processing.*-2\\.[0-9]+.*"))
        }
    }

    fun handleKspArgs(): List<String> {
        if (!options.isEnableKsp) {
            return emptyList()
        }

        kspOutputDir.deleteRecursively()
        kspCachesDir.deleteRecursively()

        // KSP2 (Kotlin 2.0+) no longer uses compiler plugin parameters
        // It runs as a standalone tool via KspAATask in Gradle daemon
        if (isKsp2) {
            // KSP2 doesn't need compiler plugin arguments
            // The generated .kt files will be collected and compiled separately
            return emptyList()
        }

        // KSP1 (Kotlin 1.x) uses compiler plugin parameters
        // see guide: https://kotlinlang.org/docs/ksp-command-line.html
        // source code: https://android.googlesource.com/platform/external/ksp/+/e8ea2ac285fc2d1ab8726b0f46d5a827cf5bf9d1/common-util/src/main/kotlin/com/google/devtools/ksp/KspOptions.kt
        // arguments in gradle: symbol-processing-gradle-plugin-1.7.21-1.0.8.jar -> com.google.devtools.ksp.gradle.KspTaskJvm.setupCompilerArgs
        // build directory: build/generated/ksp/debug, build/kspCaches/debug
        // load processor: com.google.devtools.ksp.KotlinSymbolProcessingExtension.loadProviders

        val kspArgs = mutableListOf<String>()
        kspArgs.addAll(listOf(
            // normal ksp arguments
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:projectBaseDir=${projectBaseDir}",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:classOutputDir=${kspClassOutputDir}",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:javaOutputDir=${kspJavaOutputDir}",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:kotlinOutputDir=${kspKotlinOutputDir}",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:resourceOutputDir=${kspResourceOutputDir}",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:kspOutputDir=${kspOutputDir}",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:cachesDir=${kspCachesDir}",
            // on incremental for now and conflict with withCompilation=true
//            "-P", "plugin:com.google.devtools.ksp.symbol-processing:incremental=true",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:incrementalLog=false",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:allWarningsAsErrors=false",
            "-P", "plugin:com.google.devtools.ksp.symbol-processing:apclasspath=${options.kspDependencies.joinToString(
                File.pathSeparator) { it.path }}",
        ))

        if (options.isKspWithCompilation) {
            kspArgs.addAll(listOf(
                "-P", "plugin:com.google.devtools.ksp.symbol-processing:withCompilation=true", // conflict with incremental=true
                "-P", "plugin:com.google.devtools.ksp.symbol-processing:returnOkOnError=true", // return ok even some ksp processor failed
            ))
        }

        return kspArgs
    }

    fun getOutput(task: CompileTask): List<CompileOutput> {
        if (!options.isEnableKsp) {
            return emptyList()
        }

        if (options.isKspWithCompilation) {
            return emptyList()
        }

        val output = mutableListOf<CompileOutput>()

        // KSP2: Read from Gradle's build directory
        // KSP1: Read from our temp directory
        val kspOutputDirToUse = if (isKsp2) {
            // For KSP2, try to find Gradle's KSP output directory
            val gradleKspDir = module.moduleRootDir.resolve("build/generated/ksp/debug")
            if (gradleKspDir.exists()) {
                gradleKspDir
            } else {
                kspOutputDir
            }
        } else {
            kspOutputDir
        }

        val kspKotlinDir = kspOutputDirToUse.resolve("kotlin")
        val kspJavaDir = kspOutputDirToUse.resolve("java")
        val kspClassDir = kspOutputDirToUse.resolve("classes")

        // KSP2 generates .kt files that need to be compiled separately
        // KSP1 can generate .class files directly (when withCompilation=false)
        val dirMap = if (isKsp2) {
            mapOf(
                kspKotlinDir to CompileOutput.Type.Kotlin,
                kspJavaDir to CompileOutput.Type.Java,
            )
        } else {
            mapOf(
                kspClassDir to CompileOutput.Type.Class, // KSP1 may generate
                kspKotlinDir to CompileOutput.Type.Kotlin,
                kspJavaDir to CompileOutput.Type.Java,
            )
        }

        dirMap.forEach { (dir, type) ->
            if (dir.exists()) {
                dir.listFilesRecursively().forEach { file ->
                    val targetFile = file.copyToBaseDir(dir, task.outputDir)
                    val compileOutput = CompileOutput(type, targetFile, task.outputDir)
                    output.add(compileOutput)
                }
            }
        }
        return output
    }
}
