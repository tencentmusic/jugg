package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.RPackageReader
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.*

class KotlinCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = false

    override val isNeedPrintProgress: Boolean = true

    private var hasRetryCompile = false

    private val kotlinAndroidExtensionsPath: String? by lazy { getPluginPath("kotlin-android-extensions") }

    private val isEnableKapt get() = JuggSettings.isEnableApt

    private var kotlinCompile = K2JVMCompilerIsolate()

    private val projectKotlinCompilerClasspath: List<File>? by lazy {
        logger.debug("projectKotlinCompilerClasspath start")
        val classpathMap = mutableMapOf<String, MutableSet<File>>()
        val voteMap = mutableMapOf<String, Int>()
        context.modules.values.forEach { module ->
            val kotlinCompilerClasspath = mutableListOf<File>()
            kotlinCompilerClasspath.addAll(module.kotlinPlugins ?: emptyList())
            kotlinCompilerClasspath.addAll(module.kotlinExtensions ?: emptyList())
            kotlinCompilerClasspath.filter {
                val isExists = it.exists()
                if (!isExists) logger.debug("projectKotlinCompilerClasspath not exists: ${it.path}")
                isExists
            }
            val kotlinCompilerVersion = K2JVMCompilerIsolate.getKotlinCompilerVersion(kotlinCompilerClasspath) ?: "not_found"
            classpathMap.getOrPut(kotlinCompilerVersion) { mutableSetOf() }
            // collect all available kotlin compiler classpath, some plugins may not appear in all modules
            classpathMap[kotlinCompilerVersion]!!.addAll(kotlinCompilerClasspath)

            // records which is most common one(usually project should not have second compiler, but just for safety)
            voteMap.getOrPut(kotlinCompilerVersion) { 0 }
            voteMap[kotlinCompilerVersion] = voteMap[kotlinCompilerVersion]!! + 1
        }

        logger.debug("projectKotlinCompilerClasspath classpathMap: $classpathMap")
        logger.debug("projectKotlinCompilerClasspath voteMap: $voteMap")
        val chooseVersion = voteMap
            .filter { it.key != "not_found" } // filter out not found
            .maxByOrNull { it.value } // pick the most common one
            ?.key
        val chooseClasspath = classpathMap[chooseVersion]
        logger.debug("projectKotlinCompilerClasspath chooseVersion: $chooseVersion, chooseClasspath: $chooseClasspath")

        return@lazy chooseClasspath?.toList()
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // KotlinCompiler.pluginClasspath in gradle contains all kotlin compiler classpath
        // Jugg will check it again in [initIfNeeded] before use it
        kotlinCompile.initIfNeeded(projectKotlinCompilerClasspath, logger)

        val pluginArgs = mutableListOf<String>()
        if (kotlinCompile.isUseProjectCompiler) {
            // if we use project compiler, we can use project plugins
            module.kotlinPlugins?.forEach {
                pluginArgs.add("-Xplugin=${it.path}")
            }
        } else {
            // we are using embedded compiler, which may conflict with the plugin version in project
        }

        val analyzeResult = analyzeSource(task.files.map { it.file }, module)
        logger.debug("analyzeSource result: $analyzeResult")

        if (analyzeResult.isNeedKotlinAndroidExtensions) {
            if (analyzeResult.rPackageName == null) {
                logger.warn("found KotlinAndroidExtensions, but rPackageName is null, failed to proceed.")
                val details: List<Result<CompileFile, CompileError>> = task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "rPackageName not found for KotlinAndroidExtensions")))
                }
                return CompileResult(task, details, emptyList())
            }
            if (kotlinAndroidExtensionsPath == null) {
                logger.warn("KotlinAndroidExtensions not found in classpath, can not proceed kotlin android extensions.")
                val details: List<Result<CompileFile, CompileError>> = task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "kotlinAndroidExtensionsPath not found in classpath")))
                }
                return CompileResult(task, details, emptyList())
            }
        }

        val kotlinClassPath = module.buildPathInfo.kotlinClassPath.absoluteFile

        val flavor = "main"
        // I'm not sure whether flavor should read in "MyApplication.app.main"
        // and module.name don't end with ".main" now, so just leave it default "main"
//        val splits = module.name.split(".")
//        if (splits.size >= 2) {
//            flavor = splits.last()
//        } else {
//            logger.warn("module name \"${module.name}\" is not valid, use default flavor name: $flavor")
//        }

        val resourcePaths: List<String> = task.files.flatMap {
            it.module.resourceDirs.map { file ->
                file.absolutePath
            }
        }

        val extensionArgs = mutableListOf<String>()
        if (analyzeResult.isNeedKotlinAndroidExtensions) {
            val variantArgs: List<String> = resourcePaths.flatMap { resourcePath ->
                listOf("-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}")
            }
            extensionArgs.addAll(variantArgs)
            extensionArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.android:package=${analyzeResult.rPackageName}"))
            extensionArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.android:experimental=true"))

            if (!kotlinCompile.isUseProjectCompiler) {
                // use embedded compiler, we need to add embedded plugin path
                extensionArgs.add("-Xplugin=$kotlinAndroidExtensionsPath")
            }
        }

        val kaptArgs = mutableListOf<String>()
        val kaptTmpDir = context.tempCompileDir.resolve("kapt")
        val kaptSourceDir = kaptTmpDir.resolve("sources")
        val kaptClassesDir = kaptTmpDir.resolve("classes")
        val kaptStubsDir = kaptTmpDir.resolve("stubs")
        if (kotlinCompile.isUseProjectCompiler && isEnableKapt && module.kaptDependencies.isNotEmpty()) {
            // see https://kotlinlang.org/docs/kapt.html#use-in-cli
            kaptArgs.addAll(listOf(
                // normal kapt arguments
                "-P", "plugin:org.jetbrains.kotlin.kapt3:sources=${kaptSourceDir}",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:classes=${kaptClassesDir}",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:stubs=${kaptStubsDir}",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:verbose=true",
//                "-P", "plugin:org.jetbrains.kotlin.kapt3:aptMode=stubs",
            ))

            module.kaptDependencies.forEach {
                kaptArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.kapt3:apclasspath=${it.file.path}"))
            }

            // TODO get kapt options
            val kaptOptions = encodeList(module.javaAnnotationProcessorOptions)
            if (kaptOptions != null) {
                kaptArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.kapt3:apoptions=${kaptOptions}"))
            }
        }

        val composeArgs = mutableListOf<String>()
        if (kotlinCompile.isUseProjectCompiler && analyzeResult.isNeedCompileCompose) {
            var composeExtension = module.kotlinExtensions?.find {
                it.path.contains("androidx.compose")
            }
            if (composeExtension == null) {
                // kotlin 2.0
                composeExtension = module.kotlinPlugins?.find {
                    it.path.contains("kotlin-compose-compiler")
                }
            }
            if (composeExtension == null) {
                logger.warn("Compose extension not found in classpath, compile result may be incorrect.")
            } else {
                composeArgs.add("-Xplugin=${composeExtension.path}")
                composeArgs.addAll(listOf("-P", "plugin:androidx.compose.plugins.idea:enabled=true"))
                composeArgs.addAll(listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"))
                composeArgs.add("-Xallow-unstable-dependencies")
            }
        }

        val javaSourceRoots = (module.sourceDirs + context.getGeneratedSourcePaths(module)).filter {
            it.exists()
        }

        var jvmTarget = module.kotlinJvmTarget ?: "1.8"
        if (!kotlinCompile.isUseProjectCompiler && (jvmTarget == "1.6" || jvmTarget == "1.7")) {
            logger.debug("jvm target is $jvmTarget, force to 1.8 to avoid error: " +
                    "error: JVM target 1.6 is no longer supported. Please migrate to JVM target 1.8 or above")
            jvmTarget = "1.8"
        }

        val moduleName = "${module.gradleModuleName ?: module.name}_${module.buildVariant}"
        val compileArgs = (module.kotlinFreeCompilerArgs + listOf(
            "-verbose",
            "-jvm-target", jvmTarget,
            "-nowarn",
            "-no-stdlib",
            "-no-reflect",
            "-module-name", moduleName,
            "-Xfriend-paths=${kotlinClassPath.absolutePath}",
            "-Xskip-prerelease-check", // class 'xxx' is compiled by a pre-release version of Kotlin and cannot be loaded by this version of the compiler
            "-Xallow-no-source-files",
            "-Xreport-output-files",
            // resolve "class is not abstract and does not implement abstract member"
            // resolve "reference not found" when invoke new java methods that haven't been compiled
            "-Xjava-source-roots=${javaSourceRoots.joinToString(",")}",
            // we have to set output dir to kotlin compiled class path to resolve
            // 'xxx' is a public API property declared in different module
            "-d", kotlinClassPath.absolutePath,
        )).toMutableList()
        if (!kotlinCompile.isUseProjectCompiler) {
            // use embedded compiler, we need to set the language version
            compileArgs.addAll(listOf("-language-version", guessKotlinVersion(module)))
        }

        var classPathArgs = listOf<String>()
        val dependencies = context.getModuleDependencies(module, task)
        if (dependencies.isNotEmpty()) {
            classPathArgs = listOf(
                "-cp", dependencies.joinToString(File.pathSeparator)
            )
        }

        val fileArgs = task.files.map { it.file.absolutePath }

        val command = pluginArgs + extensionArgs + kaptArgs + composeArgs + compileArgs + classPathArgs + fileArgs
        logCompileCommand(command)

        // resolve kotlin extension function unresolved reference
        val merger = KmModuleMergerForCompilation(kotlinClassPath)
        try {
            merger.loadAndMerge()
        } catch (e: Exception) {
            logger.debug("loadAndMerge .kotlin_module failed", e)
            logger.warn("Load and merge .kotlin_module failed, it may cause compile time error. Detail: ${e.message}")
        }

        val outputParser = KotlinCompilerOutputParser(task.files, logger)
        val exitCode = try {
            kotlinCompile.exec(outputParser.printStream, command.toTypedArray())
        } catch (e: Exception) {
            logger.error("invoke kotlin compile failed", e)
            ExitCode.INTERNAL_ERROR
        }
        outputParser.flush()
        logger.debug("kotlin compile result code: $exitCode")

        // retry strategy
        if (!hasRetryCompile && handleMetadataError(outputParser)) {
            hasRetryCompile = true
            logger.info("Kotlin compile failed with metadata error, retry once.")
            return doModuleCompile(task, module)
        }

        val errorResults = outputParser.results.sumOf {
            if (it.isSuccess) 0 else it.getFailure().errors.size
        }
        var shouldRecreate = false
        var retryReason = ""
        if (errorResults > JuggSettings.minErrorToRecreateCompiler) {
            // most likely kotlin compiler is not working, try to recreate once
            retryReason = "Kotlin compile failed with too many errors(> ${JuggSettings.minErrorToRecreateCompiler})"
            shouldRecreate = true
        }
        if (exitCode == ExitCode.INTERNAL_ERROR) {
            logger.warn("Kotlin compile failed with with INTERNAL_ERROR!")
            retryReason = "Kotlin compile failed with INTERNAL_ERROR"
            shouldRecreate = true
        }
        if (shouldRecreate) {
            logger.debug("try recreate compiler once, hasRecreateAfterInternalError: $hasRetryCompile")
            if (!hasRetryCompile) {
                logger.warn("\n$retryReason, retry with recreating compiler once.\n")
                hasRetryCompile = true
                kotlinCompile = K2JVMCompilerIsolate()
                return doModuleCompile(task, module)
            }
        }

        try {
            merger.loadAndMerge()
            merger.save()
        } catch (e: Exception) {
            logger.debug("loadAndMerge .kotlin_module after compile failed", e)
            logger.warn("Load and merge .kotlin_module after compile failed, it may cause compile time error later. Detail: ${e.message}")
        }

        if (exitCode != ExitCode.OK) {
            // print infos
            context.printClasspathCheck(module)
            hasRetryCompile = false
            return CompileResult(task, outputParser.results, emptyList())
        }

        // copy outputs to task.outputDir
        val outputs = outputParser.outputs.mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null

            val targetFile = if (it.isChild(kotlinClassPath)) {
                it.copyToBaseDir(kotlinClassPath, task.outputDir)
            } else if (it.isChild(kaptSourceDir)) {
                it.copyToBaseDir(kaptSourceDir, task.outputDir)
            } else if (it.isChild(kaptClassesDir)) {
                it.copyToBaseDir(kaptClassesDir, task.outputDir)
            } else {
                logger.debug("unknown output file, ignore: $it")
                return@mapNotNull null
            }
            CompileOutput(CompileOutput.Type.Class, targetFile, task.outputDir)
        }

        hasRetryCompile = false
        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }

    private fun handleMetadataError(outputParser: KotlinCompilerOutputParser): Boolean {
        if (outputParser.metadataVersionErrors.isEmpty()) {
            return false
        }

        logger.debug("found metadata error message size ${outputParser.metadataVersionErrors.size}")
        outputParser.metadataVersionErrors.forEach { metadataError ->
            try {
                val errorMerger = KmModuleMergerForCompilation(metadataError.metadataFile.parentFile.parentFile)
                errorMerger.loadAndMerge()
                errorMerger.save(metadataError.expectMetadataVersion)
                logger.debug("save ${metadataError.metadataFile} from ${metadataError.actualVersion} to " +
                        "${metadataError.expectVersion} success")
            } catch (e: Exception) {
                logger.debug("save ${metadataError.metadataFile} from ${metadataError.actualVersion} to " +
                        "${metadataError.expectVersion} .kotlin_module failed, just delete it.", e)
            }
        }
        return true
    }

    private fun logCompileCommand(options: List<String>) {
        val baseDir = context.projectDir

        var lastOption = ""
        val shortOptions = options.map {
            if (lastOption == "-cp") {
                return@map it
                    .split(File.pathSeparator)
                    .joinToString(File.pathSeparator) { cpPath ->
                        File(cpPath).relativeToOrSelf(baseDir).path
                    }
            }
            lastOption = it

            if (!it.startsWith('/')) {
                return@map it
            }
            val file = File(it).relativeToOrSelf(baseDir)
            return@map file.path
        }
        logger.debug("kotlin compile base dir: $baseDir")
        logger.debug("kotlin compile: kotlinc ${shortOptions.joinToString(" ")}")
    }

    private fun analyzeSource(files: List<File>, module: ModuleInfo): KotlinSourceAnalyzeResult {
        TimeLogger.start("analyzeSource")

        var isNeedKotlinAndroidExtensions = false
        var isNeedCompileCompose = false

        // Check features by checking import. It's not 100% accurate, but whatever.
        files.forEach root@{ file ->
            file.readLines().forEach {
                val line = it.trim()
                if (line.isEmpty()) {
                    return@forEach
                }
                if (!line.startsWith("import") && !line.startsWith("package")) {
                    // imports are only allowed in the beginning of file
                    // check import finished
                    return@root
                }

                val importContent = line.substringAfter("import").trim()
                if (importContent.startsWith("kotlinx.android.synthetic.")) {
                    logger.debug("find kotlinx.android.synthetic import in $file")
                    isNeedKotlinAndroidExtensions = true
                }
                if (importContent.startsWith("androidx.compose.")) {
                    logger.debug("find androidx.compose import in $file")
                    isNeedCompileCompose = true
                }
            }
        }

        var rPackageName: String? = null
        if (isNeedKotlinAndroidExtensions && module.buildPathInfo.mergedManifest.exists()) {
            rPackageName = RPackageReader(module.buildPathInfo.mergedManifest, logger).readPackageName()
        }

        TimeLogger.end("analyzeSource", logger)
        return KotlinSourceAnalyzeResult(isNeedKotlinAndroidExtensions, rPackageName, isNeedCompileCompose)
    }

    private var guessKotlinVersionCache = mapOf<String, String>()


    private fun guessKotlinVersion(module: ModuleInfo): String {
        guessKotlinVersionCache[module.name]?.let {
            return it
        }

        val kotlinStdlibName = module.libraryDependencies.find {
            it.file.name.contains("kotlin-stdlib")
        }?.file?.nameWithoutExtension
        logger.debug("kotlin-stdlib kotlinStdlibName $kotlinStdlibName")
        if (kotlinStdlibName == null) {
            logger.debug("kotlin-stdlib not found in module ${module.name}, can not guess kotlin version, use default ${K2JVMCompilerIsolate.VERSION}")
            return K2JVMCompilerIsolate.VERSION
        }

        val kotlinVersion = try {
            val splits = kotlinStdlibName.split("-")
            val regex = Regex("[0-9\\.]+")
            val version = splits.find { it.matches(regex) }?: throw IllegalArgumentException("not a standard stdlib")
            version.split(".").take(2).joinToString(".")
        } catch (e: Exception) {
            logger.debug("kotlin-stdlib name '$kotlinStdlibName' is not valid, can not guess kotlin version, use default ${K2JVMCompilerIsolate.VERSION}")
            return K2JVMCompilerIsolate.VERSION
        }

        if (kotlinVersion == "1.1" || kotlinVersion == "1.2" || kotlinVersion == "1.3") {
            logger.debug("guess kotlin version is $kotlinVersion, use min 1.4")
            return "1.4"
        }

        logger.debug("guess kotlin version: $kotlinVersion")
        return kotlinVersion
    }

    override fun warmUp() {
        val startTime = System.currentTimeMillis()
        val selectModule = context.modules.values
            .filter { module ->
                // don't run on java-only module, it will generate dirty .kotlin_module
                val isKotlinModule = !module.kotlinPlugins.isNullOrEmpty() ||
                        module.libraryDependencies.any { it.name.contains("kotlin-stdlib") }
                return@filter isKotlinModule
            }.maxByOrNull {
                it.moduleDependencies.size + it.libraryDependencies.size
            }
        logger.debug("start KotlinCompiler warm up, selectModule: ${selectModule?.name}")
        if (selectModule != null) {
            doModuleCompile(CompileTask(emptyList(), context.tempCompileDir, CompileStatusHolder.DEFAULT), selectModule)
        }
        logger.debug("finish KotlinCompiler warm up, cost: ${System.currentTimeMillis() - startTime}ms")
    }

    companion object {

        private fun encodeList(options: Map<String, String>?): String? {
            // see https://kotlinlang.org/docs/kapt.html#use-in-cli
            if (options == null) {
                return null
            }
            val os = ByteArrayOutputStream()
            val oos = ObjectOutputStream(os)

            oos.writeInt(options.size)
            for ((key, value) in options.entries) {
                oos.writeUTF(key)
                oos.writeUTF(value)
            }

            oos.flush()
            return Base64.getEncoder().encodeToString(os.toByteArray())
        }

        private fun getPluginPath(name: String): String? {
            val classLoader = this::class.java.classLoader
            return if (classLoader is UrlClassLoader) {
                // running in IDE
                val filePath = classLoader.urls.firstOrNull { it.file.contains(name) }?.file
                filePath?.replace("%20", " ")
            } else {
                // running in test. notion: this may cost 500+ms which will affect compile cost
                val filePath = ClassGraph().classpathFiles.firstOrNull { it.name.startsWith(name) }?.path
                filePath?.replace("%20", " ")
            }
        }
    }
}

private data class KotlinSourceAnalyzeResult(
    val isNeedKotlinAndroidExtensions: Boolean,
    val rPackageName: String?,
    val isNeedCompileCompose: Boolean,
)
