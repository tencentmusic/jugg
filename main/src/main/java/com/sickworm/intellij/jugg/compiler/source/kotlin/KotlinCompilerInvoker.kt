package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.lang.UrlClassLoader
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.*
import kotlin.collections.filter

/**
 * KotlinCompilerInvoker invokes kotlin operations and maps outputs/errors.
 */
class KotlinCompilerInvoker {

    private var hasRetryCompile = false
    private var tryDisablePlugins: List<File> = emptyList()
    private var disablePlugins: List<File> = emptyList()

    private var tryProperJvmTarget: String? = null
    private var properJvmTarget: String? = null

    private val kotlinAndroidExtensionsPath: String? by lazy { getEmbeddedJarPath("kotlin-android-extensions") }

    private var kotlinCompile = K2JVMCompilerIsolate()

    private val projectKotlinCompilerClasspathMap: MutableMap<String, List<File>> = mutableMapOf()
    private lateinit var defaultProjectKotlinCompilerClasspath: List<File>

    private fun initProjectKotlinCompilerClasspath(logger: Logger, context: ICompileContext) {
        logger.debug("projectKotlinCompilerClasspath start")
        val classpathMap = mutableMapOf<String, MutableSet<File>>()
        val versionMap = mutableMapOf<String, String>()
        val voteMap = mutableMapOf<String, MutableSet<String>>()
        context.modules.values.forEach { module ->
            var kotlinCompilerClasspath = mutableListOf<File>()
            kotlinCompilerClasspath.addAll(module.kotlinPlugins ?: emptyList())
            kotlinCompilerClasspath.addAll(module.kotlinExtensions ?: emptyList())
            kotlinCompilerClasspath.addAll(module.kspDependencies?.map { it.file } ?: emptyList())
            kotlinCompilerClasspath = kotlinCompilerClasspath
                .filter {
                    val isExists = it.exists()
                    if (!isExists) logger.debug("projectKotlinCompilerClasspath not exists: ${it.path}")
                    isExists
                }.filter {
                    // exception: java.lang.ClassCastException: Cannot cast
                    // org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
                    // to org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
                    !it.path.contains("kotlin-scripting-")
                }.toMutableList()
            val kotlinCompilerVersion = K2JVMCompilerIsolate.getKotlinCompilerVersion(kotlinCompilerClasspath) ?: "not_found"
            classpathMap.getOrPut(kotlinCompilerVersion) { mutableSetOf() }
            // collect all available kotlin compiler classpath, some plugins may not appear in all modules
            classpathMap[kotlinCompilerVersion]!!.addAll(kotlinCompilerClasspath)

            // records which is most common one(usually project should not have second compiler, but just for safety)
            voteMap.getOrPut(kotlinCompilerVersion) { mutableSetOf() }.add(module.name)
            versionMap[module.name] = kotlinCompilerVersion
        }

        logger.debug("projectKotlinCompilerClasspath classpathMap: $classpathMap")
        logger.debug("projectKotlinCompilerClasspath voteMap: $voteMap")
        val chooseVersion = voteMap
            .filter { it.key != "not_found" } // filter out not found
            .maxByOrNull { it.value.size } // pick the most common one
            ?.key
        val chooseClasspath = classpathMap[chooseVersion]
        logger.debug("projectKotlinCompilerClasspath default chooseVersion: $chooseVersion, chooseClasspath: $chooseClasspath")

        defaultProjectKotlinCompilerClasspath = chooseClasspath?.toList() ?: emptyList()
        context.modules.values.forEach { module ->
            val version = versionMap[module.name]!!
            projectKotlinCompilerClasspathMap[module.name] = classpathMap[version]!!.toList()
        }
    }

    /**
     * Options carries isEnableKapt, isNeedKotlinAndroidExtensions, isNeedCompileCompose, and rPackageName.
     */
    data class Options(
        val isEnableKapt: Boolean = false,
        val isNeedKotlinAndroidExtensions: Boolean = false,
        val isNeedCompileCompose: Boolean = false,
        val rPackageName: String? = null,
        val isCanAutoRetry: Boolean = true,
        val kaptOptions: Map<String, String> = emptyMap(),
        val kaptDependencies: List<File> = emptyList(),
        val javaSourceDirs: List<File>? = null,
        // Force using embedded compiler by bypassing project compiler classpath.
        val forceUseEmbeddedKotlinCompiler: Boolean = false,
        val isEnableKsp: Boolean = false,
        // kotlin compiler will compile file to class if ksp didn't process this file
        // so we only use isKspWithCompilation=true for now
        val isKspWithCompilation: Boolean = true,
        val kspDependencies: List<File> = emptyList(),
        val kotlinPlugins: List<File> = emptyList(),
        val kotlinExtensions: List<File> = emptyList(),
    )

    fun compile(
        context: ICompileContext,
        module: ModuleInfo,
        task: CompileTask,
        logger: Logger,
        options: Options,
    ): CompileResult {
        logger.debug("compile options: $options")

        if (!::defaultProjectKotlinCompilerClasspath.isInitialized) {
            initProjectKotlinCompilerClasspath(logger, context)
        }
        // Jugg will check it again in [initIfNeeded] before use it
        val classpath = if (options.forceUseEmbeddedKotlinCompiler) {
            logger.debug("forceUseEmbeddedKotlinCompiler enabled, bypass project compiler classpath")
            null
        } else {
            context.getParentModules(module, true)
                .firstNotNullOfOrNull {
                    val result = projectKotlinCompilerClasspathMap[it.name]
                    if (result.isNullOrEmpty()) return@firstNotNullOfOrNull null
                    result
                }
                ?: defaultProjectKotlinCompilerClasspath
        }
        kotlinCompile.initIfNeeded(classpath, logger)

        val kotlinPlugins = options.kotlinPlugins
            .filter { !disablePlugins.contains(it) && !tryDisablePlugins.contains(it) }
            .filter {
                // exception: java.lang.ClassCastException: Cannot cast
                // org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
                // to org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
                !it.path.contains("kotlin-scripting-")
            }

        val kotlinExtensions = options.kotlinExtensions
            .filter { !disablePlugins.contains(it) && !tryDisablePlugins.contains(it) }

        val pluginArgs = mutableListOf<String>()
        if (kotlinCompile.isUseProjectCompiler || options.forceUseEmbeddedKotlinCompiler) {
            // if we use project compiler, we can use project plugins
            kotlinPlugins.forEach {
                pluginArgs.add("-Xplugin=${it.path}")
            }
            // compat with kuikly
            pluginArgs.addAll(listOf("-P", "plugin:kuikly:statisticsPath=" +
                    "${context.tempCompileDir.resolve("kuikly")}"))
        } else {
            // we are using embedded compiler, which may conflict with the plugin version in project
        }

        if (options.isNeedKotlinAndroidExtensions) {
            if (options.rPackageName == null) {
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
        if (options.isNeedKotlinAndroidExtensions) {
            val variantArgs: List<String> = resourcePaths.flatMap { resourcePath ->
                listOf("-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}")
            }
            extensionArgs.addAll(variantArgs)
            extensionArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.android:package=${options.rPackageName}"))
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
        val kaptOutputDir = kaptTmpDir.resolve("output")
        val kaptIncrementalDataDir = kaptTmpDir.resolve("incrementalData")
        if (options.isEnableKapt) {
            kaptTmpDir.clearDir()

            // see https://kotlinlang.org/docs/kapt.html#use-in-cli
            kaptArgs.addAll(listOf(
                // normal kapt arguments
                "-P", "plugin:org.jetbrains.kotlin.kapt3:sources=${kaptSourceDir}",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:classes=${kaptClassesDir}",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:stubs=${kaptStubsDir}",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:incrementalData=${kaptIncrementalDataDir}", // no use for now, clear everytime
                "-P", "plugin:org.jetbrains.kotlin.kapt3:verbose=true",
                "-P", "plugin:org.jetbrains.kotlin.kapt3:correctErrorTypes=true", // recommend, just add it
                // stubs, apt, stubsAndApt, compile
                "-P", "plugin:org.jetbrains.kotlin.kapt3:aptMode=stubsAndApt",
            ))

            options.kaptDependencies.forEach {
                kaptArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.kapt3:apclasspath=${it.path}"))
            }

            val kaptOptions = (module.javaAnnotationProcessorOptions ?: emptyMap()) +
                    (module.kaptArguments ?: emptyMap()) +
                    options.kaptOptions
            if (kaptOptions.isNotEmpty()) {
                val encodedKaptOptions = encodeList(kaptOptions)
                kaptArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.kapt3:apoptions=${encodedKaptOptions}"))
            }
        }

        val kspArgsManager = KspArgsManager(module, context, options)
        val kspArgs = kspArgsManager.handleKspArgs()
        val composeArgs = handleComposeArgs(options, kotlinExtensions, kotlinPlugins, logger)

        var javaSourceRoots = if (options.javaSourceDirs != null) {
            // Explicit javaSourceDirs means caller wants a constrained source-root set.
            options.javaSourceDirs
        } else {
            module.sourceDirs + context.getGeneratedSourcePaths(module)
        }
        javaSourceRoots = javaSourceRoots.filter {
            it.exists()
        }

        var jvmTarget = tryProperJvmTarget ?: properJvmTarget
        if (jvmTarget == null) {
            jvmTarget = module.kotlinJvmTarget ?: "1.8"
            if (!kotlinCompile.isUseProjectCompiler && (jvmTarget == "1.6" || jvmTarget == "1.7")) {
                logger.debug("jvm target is $jvmTarget, force to 1.8 to avoid error: " +
                        "error: JVM target 1.6 is no longer supported. Please migrate to JVM target 1.8 or above")
                jvmTarget = "1.8"
            }
        }

        val moduleName = "${module.gradleModuleName ?: module.name}_${module.buildVariant}"
        val outputDir = if (options.isEnableKapt) {
            kaptOutputDir
        } else {
            // we have to set output dir to kotlin compiled class path to resolve
            // 'xxx' is a public API property declared in different module
            kotlinClassPath
        }
        val compileArgs = (module.kotlinFreeCompilerArgs + listOf(
//            "-X", // Display information about the advanced options and exit.
            "-verbose",
            "-jvm-target", jvmTarget,
            "-nowarn",
            "-no-stdlib",
            "-no-reflect",
            "-module-name", moduleName,
            "-Xfriend-paths=${kotlinClassPath.absolutePath}",
            "-Xskip-prerelease-check", // class 'xxx' is compiled by a pre-release version of Kotlin and cannot be loaded by this version of the compiler
            "-Xskip-metadata-version-check", // let skip-prerelease-check works for Kotlin 1.3
            "-Xallow-no-source-files",
            "-Xreport-output-files",
            // resolve "class is not abstract and does not implement abstract member"
            // resolve "reference not found" when invoke new java methods that haven't been compiled
            "-Xjava-source-roots=${javaSourceRoots.joinToString(",")}",
            "-Xallow-unstable-dependencies", // error: classes compiled by an unstable version of the Kotlin compiler were found in dependencies. Remove them from the classpath or use '-Xallow-unstable-dependencies' to suppress errors
            // we have to set output dir to kotlin compiled class path to resolve
            // 'xxx' is a public API property declared in different module
            "-d", outputDir.path,
        )).toMutableList()
        if (!kotlinCompile.isUseProjectCompiler) {
            // use embedded compiler, we need to set the language version
            compileArgs.addAll(listOf("-language-version", "1.9"))
        }

        var classPathArgs = listOf<String>()
        val dependencies = context.getModuleDependencies(module, task)
        if (dependencies.isNotEmpty()) {
            classPathArgs = listOf(
                "-cp", dependencies.joinToString(File.pathSeparator)
            )
        }

        // align with AGP/KGP: resolve java.* from android.jar instead of mounting the host JDK,
        // which old compilers (< 2.1.20) can not handle on JDK 25+ hosts
        if (KotlinCompilerHostCompat.shouldUseNoJdk(Runtime.version().feature(), dependencies)) {
            logger.debug("host JDK ${Runtime.version().feature()} with android.jar dependency, add -no-jdk")
            compileArgs.add("-no-jdk")
        }

        val fileArgs = task.files.map { it.file.absolutePath }

        val command = pluginArgs + extensionArgs + kaptArgs + kspArgs + composeArgs + compileArgs + classPathArgs + fileArgs
        logCompileCommand(command, context.projectDir, logger)

        // resolve kotlin extension function unresolved reference
        val merger = IKmModuleMergerForCompilation.create(kotlinCompile.currentCompiler, kotlinClassPath, logger)
        try {
            merger.loadAndMerge()
        } catch (e: Exception) {
            logger.debug("loadAndMerge .kotlin_module failed", e)
            logger.warn("Load and merge .kotlin_module failed, it may cause compile time error. Detail: ${e.message}")
        }

        val outputParser = KotlinCompilerOutputParser(
            files = task.files,
            logger = logger,
            forceCompilerOutputDebug = options.isEnableKapt,
        )
        val exitCode = try {
            kotlinCompile.exec(outputParser.printStream, command.toTypedArray())
        } catch (e: Exception) {
            logger.error("invoke kotlin compile failed", e)
            ExitCode.INTERNAL_ERROR
        }
        outputParser.flush()
        logger.debug("kotlin compile result code: $exitCode")

        // retry strategy
        if (options.isCanAutoRetry && !hasRetryCompile && handleMetadataError(outputParser, logger)) {
            hasRetryCompile = true
            logger.info("Kotlin compile failed with metadata error, retry once.")
            return compile(context, module, task, logger, options)
        }

        val isCompileSuccess = exitCode == ExitCode.OK
        val compileResults = outputParser.getResult(isCompileSuccess)
        val errorResults = compileResults.sumOf {
            if (it.isSuccess) 0 else it.getFailure().errors.size
        }
        var shouldRecreate = false
        var retryReason = ""
        if (exitCode != ExitCode.OK && errorResults > JuggSettings.minErrorToRecreateCompiler) {
            // most likely kotlin compiler is not working, try to recreate once
            retryReason = "Kotlin compile failed with too many errors(> ${JuggSettings.minErrorToRecreateCompiler})"
            shouldRecreate = true
        }
        if (exitCode == ExitCode.INTERNAL_ERROR) {
            logger.warn("Kotlin compile failed with with INTERNAL_ERROR!")
            retryReason = "Kotlin compile failed with INTERNAL_ERROR"
            shouldRecreate = true
        }

        // handles plugin arguments
        // error: required plugin option not present: kuikly:statisticsPath
        // Plugin "kuikly" usage:
        //  statisticsPath string      statistics path to save build data (required)
        val noOptionPlugins = mutableListOf<File>()
        compileResults.forEach { result ->
            if (!result.isFailed) {
                return@forEach
            }
            val regex = Regex("Plugin \"(.*)\" usage")
            for (error in result.getFailure().errors) {
                val message = error.second
                // Plugin "(.*)" usage
                val pluginName = regex.find(message)?.groupValues?.get(1)
                if (pluginName != null) {
                    val relativePlugins = (kotlinPlugins + kotlinExtensions).filter {
                        it.path.contains(pluginName, ignoreCase = true)
                    }
                    noOptionPlugins.addAll(relativePlugins)
                }
            }
        }
        if (noOptionPlugins.isNotEmpty()) {
            logger.debug("Plugin option not set, try to recreate compiler once. noOptionPlugins: $noOptionPlugins")
            retryReason = "Plugin option not set"
            shouldRecreate = true
            tryDisablePlugins = noOptionPlugins
        }

        // handles exception: java.lang.ClassCastException: Cannot cast org.jetbrains.kotlin.parcelize.ParcelizeComponentRegistrar
        // to org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
        val parcelizePlugins = mutableListOf<File>()
        if (outputParser.isGotParcelizeClassCastException) {
            val relativePlugins = (kotlinPlugins + kotlinExtensions).filter {
                it.path.contains("parcelize", ignoreCase = true)
            }
            parcelizePlugins.addAll(relativePlugins)
        }
        if (parcelizePlugins.isNotEmpty()) {
            logger.debug("Plugin parcelize not working, try to recreate compiler once. parcelizePlugins: $parcelizePlugins")
            retryReason = "Parcelize not working"
            shouldRecreate = true
            tryDisablePlugins += parcelizePlugins
        }

        // handles -jvm-target not proper:
        // cannot inline bytecode built with JVM target 21 into bytecode that is being built with JVM target 1.8. Specify proper '-jvm-target' option.
        var properJvmTargetInError: String? = null
        compileResults.forEach { result ->
            if (!result.isFailed) {
                return@forEach
            }
            val regex = Regex("cannot inline bytecode built with JVM target (.*) into bytecode that is being built with JVM target (.*)")
            for (error in result.getFailure().errors) {
                val message = error.second
                properJvmTargetInError = regex.find(message)?.groupValues?.get(1)
            }
        }
        if (properJvmTargetInError != null) {
            logger.debug("Jvm target not proper, try to recreate compiler once. properJvmTarget: $properJvmTargetInError")
            retryReason = "Jvm target not proper"
            shouldRecreate = true
            tryProperJvmTarget = properJvmTargetInError
        }

        if (shouldRecreate) {
            logger.debug("try recreate compiler once, hasRecreateAfterInternalError: $hasRetryCompile")
            if (options.isCanAutoRetry && !hasRetryCompile) {
                logger.warn("\n$retryReason, retry with recreating compiler once.\n")
                hasRetryCompile = true
                kotlinCompile = K2JVMCompilerIsolate()
                return compile(context, module, task, logger, options)
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
            return CompileResult(task, compileResults, emptyList())
        }

        // copy outputs to task.outputDir
        val compileOutputs = outputParser.outputs.mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null

            val targetFile = if (it.isChild(kotlinClassPath)) {
                it.copyToBaseDir(kotlinClassPath, task.outputDir)
            } else if (it.isChild(kaptSourceDir)) {
                it.copyToBaseDir(kaptSourceDir, task.outputDir)
            } else {
                logger.debug("unknown output file, ignore: $it")
                return@mapNotNull null
            }
            CompileOutput(CompileOutput.Type.Class, targetFile, task.outputDir)
        }

        // outputParser is unable to capture kapt outputs correctly, collect them manually here
        val kaptOutputs = mutableListOf<CompileOutput>()
        if (options.isEnableKapt) {
            // collect kapt output
            val kaptSourceOutputs = kaptSourceDir.listFilesRecursively()
                .filter {
                    it.extension == "java"
                }.map {
                    val targetFile = it.copyToBaseDir(kaptSourceDir, task.outputDir)
                    CompileOutput(CompileOutput.Type.Java, targetFile, task.outputDir)
                }
            kaptOutputs.addAll(kaptSourceOutputs)
            val kaptAptOutputs = outputDir.listFilesRecursively()
                .filter {
                    it.extension == "class"
                }.map {
                    val targetFile = it.copyToBaseDir(outputDir, task.outputDir)
                    CompileOutput(CompileOutput.Type.Class, targetFile, task.outputDir)
                }
            kaptOutputs.addAll(kaptAptOutputs)
        }

        hasRetryCompile = false
        disablePlugins = tryDisablePlugins
        properJvmTarget = tryProperJvmTarget
        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            outputs = compileOutputs + kaptOutputs + kspArgsManager.getOutput(task),
        )
    }

    private fun handleComposeArgs(
        options: Options,
        kotlinExtensions: List<File>,
        kotlinPlugins: List<File>,
        logger: Logger,
    ): List<String> {
        if (!options.isNeedCompileCompose) {
            return emptyList()
        }

        // need compile compose
        if (!kotlinCompile.isUseProjectCompiler) {
            logger.warn("It seems you're compiling compose, but compose plugin is not enabled.")
            if (!JuggSettings.isUseProjectKotlinCompiler) {
                logger.warn("Please enable \"Enable use project Kotlin compiler\" in Jugg run configurations to avoid runtime crash.")
            } else {
                logger.warn("Please try sync and fallback once, if it's still not working, please report to the admin.")
            }
            return emptyList()
        }

        // collect compose arguments
        val composeArgs = mutableListOf<String>()

        // android compose
        var composeExtension = kotlinExtensions.find {
            it.path.contains("androidx.compose")
        }

        // kmm compose
        if (composeExtension == null) {
            composeExtension = kotlinPlugins.find {
                it.path.contains("org.jetbrains.compose")
            }
        }
        // kotlin 2.0
        if (composeExtension == null) {
            composeExtension = kotlinPlugins.find {
                it.path.contains("kotlin-compose-compiler")
            }
        }

        if (composeExtension != null) {
            composeArgs.add("-Xplugin=${composeExtension.path}")
            // no idea whether it's working
            composeArgs.addAll(listOf("-P", "plugin:androidx.compose.plugins.idea:enabled=true"))
            composeArgs.addAll(listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"))
            return composeArgs
        }


        logger.debug("Compose extension not found in classpath, " +
                "kotlinPlugins: ${kotlinPlugins}, kotlinExtensions: $kotlinExtensions")
        logger.warn("Compose extension not found in classpath, compile result may be incorrect.")
        return emptyList()
    }

    private fun handleMetadataError(outputParser: KotlinCompilerOutputParser, logger: Logger): Boolean {
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

    private fun logCompileCommand(options: List<String>, baseDir: File, logger: Logger) {
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

    private var guessKotlinVersionCache = mapOf<String, String>()


    private fun guessKotlinVersionForEmbedded(module: ModuleInfo, logger: Logger): String {
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
            val regex = Regex("[0-9.]+")
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
        if (kotlinVersion in listOf("2.2", "2.3", "2.4", "2.5", "2.6", "2.7", "2.8", "2.9", "2.10")) {
            logger.debug("guess kotlin version is $kotlinVersion, use max 2.1")
            return "2.1"
        }

        logger.debug("guess kotlin version: $kotlinVersion")
        return kotlinVersion
    }

    companion object {

        private fun encodeList(options: Map<String, String>): String {
            // see https://kotlinlang.org/docs/kapt.html#use-in-cli
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

        @Suppress("SameParameterValue")
        fun getEmbeddedJarPath(name: String): String? {
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
