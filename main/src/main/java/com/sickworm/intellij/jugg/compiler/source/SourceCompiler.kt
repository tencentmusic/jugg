package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.compiler.obfuscation.DexMinifyCompiler
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompiler
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

class SourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin, CompileFile.Type.Class)

    private val javaCompiler = JavaCompiler(context.subContext("tmp_java"), this)

    private val kotlinCompiler = KotlinCompiler(context.subContext("tmp_kotlin"), this)

    private val dexCompiler = DexCompiler(context.subContext("tmp_dex"), this)

    private val dexMinify = DexMinifyCompiler(context.subContext("minify"), this)

    private val dataBindingGenMapperCompiler = DataBindingGenMapperCompiler(context.subContext("databinding"), this)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        context.tempCompileDir.clearDir()
        val compileTask = CompileTask(
            files = task.files,
            outputDir = context.tempCompileDir,
            parentTask = task,
        )
        var classCompileResult = CompileResult(compileTask, emptyList(), emptyList())

        // === NEW: Process DataBinding Mapper after source compilation ===
        // At this point, Java/Kotlin classes are compiled, so annotation processor can access .class files
        val dataBindingMapperResult = processDataBindingMapper(task, module)
        if (!dataBindingMapperResult.isAllSuccess) {
            logger.warn("DataBinding Mapper generation failed")
            return classCompileResult.failedAll(task, "DataBinding Mapper generation failed")
        }
        // Compile DataBinding generated Java files (XXXBindingImpl, BR, DataBinderMapper, etc.)
        val dataBindingJavaFiles = dataBindingMapperResult.outputs
            .filter { it.type == CompileOutput.Type.Java }
            .map { CompileFile(CompileFile.Type.Java, it.file, it.baseDir, module) }
        // === END: DataBinding Mapper processing ===
        // TODO
        // TODO 1. apt first, if DataBindingClassFile use a changed source file especially in kotlin, apt compile failed
        // TODO 2. source first, if source file use DataBindingClassFile, source compile failed
        // TODO 3. standard way, use kapt/apt with source files in. but environment is broken when use kapt(Android Studio use JVM21, run kapt requires Kotlin 1.9, 1.7 will have JAVA module error)
        // TODO 3. ps: maybe can use embedded compiler（1。9）?

        // Kotlin must go first because in the cross-reference case, Java depends on Kotlin compile output
        // while Kotlin don't (kotlin can use -Xjava-source-roots argument)
        var kotlinAptJavaFiles = emptyList<CompileFile>()
        val kotlinCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Kotlin },
            outputDir = File(context.tempCompileDir, "kotlin"),
            parentTask = compileTask,
        )
        if (kotlinCompileTask.isNeedCompile) {
            val kotlinCompileResult = kotlinCompiler.compile(kotlinCompileTask)
            if (!kotlinCompileResult.isAllSuccess) {
                val otherDetails: List<Result<CompileFile, CompileError>> = task.files
                    .filter { it.type != CompileFile.Type.Kotlin }
                    .map {
                        Result.failure(CompileError(it, listOf(-1L to "Kotlin compile failed, skip")))
                    }
                return CompileResult(task, kotlinCompileResult.details + otherDetails, kotlinCompileResult.outputs)
            }

            kotlinAptJavaFiles = kotlinCompileResult.outputs
                .filter { it.type == CompileOutput.Type.Java }
                .map { CompileFile(CompileFile.Type.Java, it.file, it.baseDir, module) }
            classCompileResult += kotlinCompileResult
        }
        if (!classCompileResult.isAllSuccess) {
            return classCompileResult.quickFailedOthers(task, isClearOutput = true)
        }

        val javaCompileTask = CompileTask(
            files = task.files.filter { it.type == CompileFile.Type.Java } + kotlinAptJavaFiles + dataBindingJavaFiles,
            outputDir = File(context.tempCompileDir, "java"),
            parentTask = compileTask,
        )
        if (javaCompileTask.isNeedCompile) {
            classCompileResult += javaCompiler.compile(javaCompileTask)
        }
        if (!classCompileResult.isAllSuccess) {
            return classCompileResult.quickFailedOthers(task, isClearOutput = true)
        }

        // e.g. META-INF/service/xxx
        val otherOutputs = classCompileResult.outputs.filter {
            it.type != CompileOutput.Type.Class
        }

        // minify by mapping for minified apk
        val classFiles = classCompileResult.outputs.filter {
            it.type == CompileOutput.Type.Class
        }
        val compileClassFiles = classFiles.map {
            CompileFile(CompileFile.Type.Class, it.file, it.baseDir, module)
        } + task.files.filter {
            it.type == CompileFile.Type.Class
        }

        val dexOutputDir = if (context.isMinified) File(context.tempCompileDir, "un_minify") else task.outputDir
        val dexTask = CompileTask(compileClassFiles, dexOutputDir, task)
        val dexCompileResult = dexCompiler.compile(dexTask)
        if (!dexCompileResult.isAllSuccess) {
            return classCompileResult.failedAll(task,"Dex compile failed")
        }

        if (context.isMinified) {
            val compileDexFiles = dexCompileResult.outputs.map {
                CompileFile(CompileFile.Type.Dex, it.file, it.baseDir, module)
            }
            val minifyTask = CompileTask(compileDexFiles, task.outputDir, task)
            val minifyResult = dexMinify.compile(minifyTask)
            if (!minifyResult.isAllSuccess) {
                return classCompileResult.failedAll(task, "Minify failed")
            }
            return CompileResult(task, classCompileResult.details, minifyResult.outputs + otherOutputs)
        }

        return CompileResult(task, classCompileResult.details, dexCompileResult.outputs + otherOutputs)
    }

    override fun warmUp() {
        kotlinCompiler.warmUp()
    }

    /**
     * Process DataBinding Mapper generation after source code compilation
     *
     * This method generates DataBinding implementation classes (XXXBindingImpl, BR, DataBinderMapper)
     * after Java/Kotlin source files are compiled to .class files.
     *
     * @param task The compile task
     * @param module The module info
     * @return CompileResult with generated Java files
     */
    private fun processDataBindingMapper(task: CompileTask, module: ModuleInfo): CompileResult {
        // Find layout info files generated by ResourceCompiler
        val layoutInfoFiles = findLayoutInfoFiles(module)
        if (layoutInfoFiles.isEmpty()) {
            logger.debug("No layout info files found, skip DataBinding Mapper generation")
            return CompileResult(task, emptyList(), emptyList())
        }

        logger.info("Processing DataBinding Mapper generation for module ${module.name}...")
        logger.debug("Found ${layoutInfoFiles.size} layout info files")
        TimeLogger.start("databinding_mapper")

        try {
            // Create DataBinding Mapper compilation task
            val sourceFiles = task.files.filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
            val dataBindingTask = CompileTask(
                files = layoutInfoFiles.map {
                    CompileFile(CompileFile.Type.Resource, it, it.parentFile, module)
                } + sourceFiles,
                outputDir = File(context.tempCompileDir, "databinding_mapper"),
                parentTask = task,
            )

            // Generate DataBinding Mapper classes
            val result = dataBindingGenMapperCompiler.compile(dataBindingTask)
            TimeLogger.end("databinding_mapper", logger)

            if (!result.isAllSuccess) {
                logger.warn("DataBinding Mapper generation failed for module ${module.name}")
            } else {
                logger.info("DataBinding Mapper generation completed for module ${module.name}")
            }

            return result
        } catch (e: Exception) {
            TimeLogger.end("databinding_mapper", logger)
            logger.warn("DataBinding Mapper generation exception: ${e.message}", e)
            return CompileResult(
                task,
                task.files.map { Result.failure(CompileError(it, listOf(-1L to "DataBinding Mapper generation failed: ${e.message}"))) },
                emptyList()
            )
        }
    }

    /**
     * Find layout info files generated by DataBindingGenBaseClassesCompiler in ResourceCompiler
     *
     * Layout info files are stored in a shared temp directory managed by ResourceOverlayCompiler.
     * We need to look in the overlays/flat_compile/databinding sub-context area, not in our own
     * classes sub-context area.
     *
     * @param module The module info
     * @return List of layout info files (.xml files containing binding information)
     */
    private fun findLayoutInfoFiles(module: ModuleInfo): List<File> {
        // Important: We need to look in the ResourceCompiler's context, not SourceCompiler's context
        // ResourceCompiler uses context.subContext("overlays").subContext("flat_compile").subContext("databinding")
        // So the layout info files are at: {root_tempCompileDir}/overlays/flat_compile/databinding/intermediates/...

        // Get the root tempCompileDir by going up from our sub-context
        val rootTempCompileDir = context.tempCompileDir.parentFile ?: context.tempCompileDir
        val resourceCompilerTempDir = File(rootTempCompileDir, "overlays/flat_compile/databinding")

        // Create ArgsManager with the correct context path
        val resourceContext = object : ICompileContext by context {
            override val tempCompileDir: File get() = resourceCompilerTempDir
        }

        val argsManager = DataBindingArgsManager(resourceContext, module)
        val layoutInfoDir = argsManager.tempDataBindingLayoutXmlDir

        if (!layoutInfoDir.exists()) {
            logger.debug("Layout info directory not found: ${layoutInfoDir.absolutePath}")
            return emptyList()
        }

        val layoutInfoFiles = layoutInfoDir.listFiles()?.filter { it.extension == "xml" } ?: emptyList()
        logger.debug("Found ${layoutInfoFiles.size} layout info files in ${layoutInfoDir.absolutePath}")

        return layoutInfoFiles
    }
}
