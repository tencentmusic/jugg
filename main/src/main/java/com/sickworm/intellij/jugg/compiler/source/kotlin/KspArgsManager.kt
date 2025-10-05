package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker.Options
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

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

    fun handleKspArgs(): List<String> {
        if (!options.isEnableKsp) {
            return emptyList()
        }

        kspOutputDir.deleteRecursively()
        kspCachesDir.deleteRecursively()

        // Kotlin will not compile kotlin to class when ksp is enabled.

        // see guide: https://kotlinlang.org/docs/ksp-command-line.html
        // source code: https://android.googlesource.com/platform/external/ksp/+/e8ea2ac285fc2d1ab8726b0f46d5a827cf5bf9d1/common-util/src/main/kotlin/com/google/devtools/ksp/KspOptions.kt
        // arguments in gradle: symbol-processing-gradle-plugin-1.7.21-1.0.8.jar -> com.google.devtools.ksp.gradle.KspTaskJvm.setupCompilerArgs
        // build directory: build/generated/ksp/debug, build/kspCaches/debug
        // load processor: com.google.devtools.ksp.KotlinSymbolProcessingExtension.loadProviders
        // FIXME ksp won't work for Kotlin 2.1 with k2 enabled. unless use -language-version=1.9

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
        val dirMap = mapOf(
            kspClassOutputDir to CompileOutput.Type.Class, // seems will not generate
            kspKotlinOutputDir to CompileOutput.Type.Kotlin,
            kspJavaOutputDir to CompileOutput.Type.Java, // seems will not generate
//            kspResourceOutputDir to CompileOutput.Type.Res, // will generate, proguard files which will not embedded into apk
        )
        dirMap.forEach { (dir, type) ->
            dir.listFilesRecursively().forEach { file ->
                val targetFile = file.copyToBaseDir(dir, task.outputDir)
                val compileOutput = CompileOutput(type, targetFile, task.outputDir)
                output.add(compileOutput)
            }
        }
        return output
    }
}