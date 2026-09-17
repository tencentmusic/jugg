package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.*
import org.gradle.api.Project
import org.gradle.api.initialization.IncludedBuild
import org.gradle.util.GradleVersion
import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Implementation of readProjectInfo.gradle.kts
 */
@Suppress("unused")
class GradleProjectInfoReaderManager(
    private val rootProject: Project,
    private val includeBuildProjects: Collection<IncludedBuild>,
) {

    // Prefer jugg.projectDir property to support projects where Gradle root != IDE project dir
    // (e.g., kugou_like/ project with android/ as Gradle root).
    private val ideProjectDir =
        rootProject.properties["jugg.projectDir"]?.toString()?.let { File(it) }
            ?: rootProject.rootDir
    private val juggPathManager = JuggPathManager(
        if (rootProject.gradle.parent == null) ideProjectDir else rootProject.rootDir
    )

    fun readAndSave() {
        try {
            if (rootProject.projectDir.name == "buildSrc") {
                println("Jugg: skip readProjectInfo.gradle for buildSrc: ${rootProject.projectDir}")
                return
            }
            val isDiffMode = rootProject.properties[PARAM_DIFF_MODE] == "true"
            val includeAndroidTestSourceSet = includeAndroidTestSourceSet()
            println("Jugg: readProjectInfo.gradle execute start, diffMode: $isDiffMode, " +
                    "includeAndroidTestSourceSet: $includeAndroidTestSourceSet, " +
                    "includeBuildProjects: ${includeBuildProjects.map { it.projectDir }}")
            val agpR8Classpath = readEnvironment()
            val startTime = System.currentTimeMillis()
            val lastProjectInfo = readLastProjectInfo()
            val projectInfo = GradleProjectInfoReader(rootProject, lastProjectInfo, ideProjectDir)
                .getProjectInfo(includeAndroidTestSourceSet)
                .copy(agpR8Classpath = agpR8Classpath)

            if (isDiffMode) {
                GradleDependencyDiffer(rootProject, projectInfo, ideProjectDir).outputDiffToDir()
            } else {
                writeProjectInfoFile(projectInfo)
                writeIncludeProjectsFile()
                refreshNativeStripCache(projectInfo)
                GradleDependencyDiffer(rootProject, projectInfo, ideProjectDir).deleteTmpProjectInfos()
            }

            val costTime = System.currentTimeMillis() - startTime
            println("Jugg: readProjectInfo.gradle execute success, cost: ${costTime}ms")
        } catch (e: Throwable) {
            println("Jugg: readProjectInfo.gradle execute failed: $e")
            printException(e)
        }
    }

    private fun readEnvironment(): File? {
        try {
            val gradleVersion = GradleVersion.current()
            val agpVersion = checkAgpVersion(rootProject)
            val agpR8Classpath = findAgpR8Classpath(rootProject)
            println("Jugg: readEnvironment gradleVersion: $gradleVersion, agpVersion: $agpVersion, " +
                    "agpR8Classpath: $agpR8Classpath")
            return agpR8Classpath
        } catch (e: Throwable) {
            println("Jugg: readProjectInfo.gradle readEnvironment failed: $e")
            printException(e)
            return null
        }
    }

    private fun findAgpR8Classpath(rootProject: Project): File? {
        val androidPluginIds = listOf(
            "com.android.application",
            "com.android.library",
            "com.android.dynamic-feature",
            "com.android.kotlin.multiplatform.library",
        )
        val project = rootProject.allprojects.find {
            androidPluginIds.any { pluginId -> it.plugins.hasPlugin(pluginId) }
        } ?: return null
        val plugin = project.plugins.findPlugin("com.android.base")
            ?: androidPluginIds.mapNotNull { project.plugins.findPlugin(it) }.firstOrNull()
            ?: return null
        val d8Class = plugin::class.java.classLoader.loadClass("com.android.tools.r8.D8")
        val location = d8Class.protectionDomain.codeSource?.location ?: return null
        val runtimeClasspath = File(location.toURI()).canonicalFile.takeIf { it.exists() }
            ?: return null
        if (!isGradleInstrumentedClasspath(runtimeClasspath, rootProject.gradle.gradleUserHomeDir)) {
            return runtimeClasspath
        }
        return findOriginalAgpR8Classpath(project, rootProject, runtimeClasspath).also {
            if (it == null) {
                println("Jugg: original AGP R8 artifact not found for instrumented classpath: " +
                        runtimeClasspath)
            }
        }
    }

    private fun findOriginalAgpR8Classpath(
        androidProject: Project,
        rootProject: Project,
        runtimeClasspath: File,
    ): File? {
        val projects = if (androidProject == rootProject) {
            listOf(rootProject)
        } else {
            listOf(androidProject, rootProject)
        }
        projects.forEach { project ->
            val classpath = project.buildscript.configurations.findByName("classpath")
                ?: return@forEach
            try {
                classpath.files.firstOrNull { it.isFile && it.name == runtimeClasspath.name }
                    ?.canonicalFile
                    ?.takeIf { it != runtimeClasspath }
                    ?.let { return it }
            } catch (e: Throwable) {
                println("Jugg: resolve original AGP R8 artifact from ${project.path} failed: $e")
            }
        }
        return null
    }

    private fun isGradleInstrumentedClasspath(classpath: File, gradleUserHomeDir: File): Boolean {
        val cachesDir = File(gradleUserHomeDir, "caches").canonicalFile
        var parent = classpath.parentFile
        while (parent != null && parent != cachesDir) {
            if (parent.parentFile == cachesDir &&
                (parent.name.startsWith("jars-") || parent.name.startsWith("transforms-"))) {
                return true
            }
            parent = parent.parentFile
        }
        return false
    }

    private fun checkAgpVersion(rootProject: Project): String {
        val project = rootProject.subprojects.find { it.plugins.hasPlugin("com.android.application") }
        if (project == null) return "no_application_module"
        try {
            val plugin = project.plugins.findPlugin("com.android.base")
                ?: return "no_plugin"
            val versionClass = try {
                plugin::class.java.classLoader.loadClass("com.android.Version")
            } catch (exception: ClassNotFoundException) {
                plugin::class.java.classLoader.loadClass("com.android.builder.model.Version")
            } catch (ex: ClassNotFoundException) {
                return "no_version_class"
            }
            val field = versionClass.fields.find { it.name == "ANDROID_GRADLE_PLUGIN_VERSION" }
                ?: return "no_version_field"
            return field.get(null) as String
        } catch (ex: Throwable) {
            return "throwable_$ex"
        }
    }


    private fun Project.toStandardModuleName(): String {
        return path.replace(":", ".").substring(1)
    }

    /**
     * Injects androidTest assemble task before Gradle finalizes the task graph.
     */
    fun injectAndroidTestTaskIfNeeded() {
        if (!includeAndroidTestSourceSet()) {
            return
        }

        val requestTasks = rootProject.gradle.startParameter.taskRequests.flatMap { it.args }
        val requestedTaskSet = requestTasks.toSet()
        val targetTasks = findTasksByRequests(requestedTaskSet)
        if (targetTasks.isEmpty()) {
            println("Jugg: no requested task found for androidTest injection, requested: $requestedTaskSet")
            return
        }
        injectApplicationAndroidTestTasks(requestTasks, targetTasks)
        readLibraryTestTasks().forEach { taskName ->
            val libraryTestTask = findTasksByRequests(setOf(taskName)).firstOrNull() ?: run {
                println("Jugg: library androidTest task $taskName not found")
                return@forEach
            }
            targetTasks.forEach { task ->
                if (task != libraryTestTask) {
                    task.dependsOn(libraryTestTask)
                    println("Jugg: inject ${libraryTestTask.path} before ${task.path}")
                }
            }
        }
    }

    /**
     * Adds lightweight reader tasks from configured included builds before the requested root-build tasks.
     */
    fun injectIncludedBuildProjectInfoTasks() {
        if (isExternalBuildInfoCollection()) {
            return
        }
        if (includeBuildProjects.isEmpty()) {
            return
        }
        val requestedTaskSet = rootProject.gradle.startParameter.taskRequests
            .flatMap { it.args }
            .toSet()
        val targetTasks = findTasksByRequests(requestedTaskSet)
        if (targetTasks.isEmpty()) {
            println("Jugg: no requested task found for included build project info, requested: $requestedTaskSet")
            return
        }
        includeBuildProjects.forEach { includedBuild ->
            val projectInfoTask = includedBuild.task(READ_PROJECT_INFO_TASK_PATH)
            targetTasks.forEach { targetTask ->
                targetTask.dependsOn(projectInfoTask)
                println("Jugg: inject $projectInfoTask before ${targetTask.path}")
            }
        }
    }

    /** Configures the invocation-scoped collector with task ordering before task execution plan is finalized. */
    fun configureExternalBuildInfoCollector() {
        if (!isExternalBuildInfoCollection()) {
            return
        }
        val collector = rootProject.tasks.maybeCreate(COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME)
        val localTaskPaths = readExternalBuildInfoRequests().mapNotNull { request ->
            val matchesLocalProject = rootProject.allprojects.any {
                it.projectDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize()
            }
            if (matchesLocalProject) request.taskPath else null
        }.distinct()
        if (localTaskPaths.isNotEmpty()) {
            collector.mustRunAfter(localTaskPaths)
        }
        includeBuildProjects.forEach { includedBuild ->
            collector.dependsOn(includedBuild.task(COLLECT_EXTERNAL_BUILD_INFO_TASK_PATH))
        }
    }

    /** Re-reads only requested external build records and writes one atomic result per build root. */
    fun collectExternalBuildInfo() {
        if (!isExternalBuildInfoCollection()) {
            return
        }
        val invocationId = rootProject.properties[PARAM_EXTERNAL_BUILD_INVOCATION]?.toString() ?: return
        val outputDir = rootProject.properties[PARAM_EXTERNAL_BUILD_OUTPUT]?.toString()?.let(::File) ?: return
        val requests = readExternalBuildInfoRequests().filter { request ->
            rootProject.allprojects.any {
                it.projectDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize()
            }
        }
        if (requests.isEmpty()) {
            return
        }
        val lastProjectInfo = readLastProjectInfo()?.let {
            JuggProjectInfoSerialize.deserialize(it, isSkipVersionCheck = true)
        } ?: throw IllegalStateException("Jugg project info is unavailable for external build collection")
        val updates = requests.mapNotNull { request ->
            val project = rootProject.allprojects.firstOrNull {
                it.projectDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize()
            } ?: return@mapNotNull null
            val module = lastProjectInfo.modules.values.firstOrNull {
                it.moduleRootDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize() &&
                        it.buildVariant == request.buildVariant
            } ?: throw IllegalStateException("Jugg module not found for ${request.moduleRootDir}")
            val buildInfo = GradleProjectInfoReader(rootProject, null, ideProjectDir)
                .getExternalBuildInfos(project, module)
                .singleOrNull { it.type == request.type }
                ?: throw IllegalStateException("External build info not found for ${request.taskPath}")
            ExternalBuildInfoUpdate(
                moduleName = module.name,
                moduleRootDir = module.moduleRootDir,
                buildVariant = module.buildVariant,
                previousTaskPath = request.taskPath,
                externalBuildInfo = buildInfo,
                strippedNativeOutput = if (request.type == ExternalBuildType.Cpp) {
                    stripExternalNativeOutput(request, buildInfo.nativeOutput, outputDir)
                } else {
                    null
                },
            )
        }
        if (updates.isEmpty()) {
            return
        }
        outputDir.mkdirs()
        val outputFile = File(outputDir, "external_build_info_${rootProject.rootDir.absolutePath.hashCode()}.json")
        val tempFile = File(outputDir, outputFile.name + ".tmp")
        tempFile.writeText(ProjectInfoSerializerInGradle.getJsonGenerator().toJson(
            ExternalBuildInfoUpdateResult(invocationId, updates),
        ))
        publishAtomically(tempFile, outputFile)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readExternalBuildInfoRequests(): List<ExternalBuildInfoRequestItem> {
        val requestFile = rootProject.properties[PARAM_EXTERNAL_BUILD_REQUEST]?.toString()?.let(::File)
            ?: return emptyList()
        val root = JsonSlurper().parse(requestFile) as? Map<String, Any> ?: return emptyList()
        val invocationId = root["invocationId"] as? String ?: return emptyList()
        if (invocationId != rootProject.properties[PARAM_EXTERNAL_BUILD_INVOCATION]?.toString()) {
            return emptyList()
        }
        return (root["items"] as? List<Map<String, Any>>).orEmpty().mapNotNull { item ->
            val type = (item["type"] as? String)?.let {
                runCatching { ExternalBuildType.valueOf(it) }.getOrNull()
            } ?: return@mapNotNull null
            ExternalBuildInfoRequestItem(
                moduleName = item["moduleName"] as? String ?: return@mapNotNull null,
                moduleRootDir = File(item["moduleRootDir"] as? String ?: return@mapNotNull null),
                buildVariant = item["buildVariant"] as? String ?: return@mapNotNull null,
                taskPath = item["taskPath"] as? String ?: return@mapNotNull null,
                type = type,
                // Absent in requests written before selective strip was added; the C++ path then fails
                // explicitly instead of silently deploying unstripped module output.
                apkOwnerModuleRootDir = (item["apkOwnerModuleRootDir"] as? String)?.let(::File),
                apkOwnerBuildVariant = item["apkOwnerBuildVariant"] as? String,
            )
        }
    }

    /**
     * Caches the APK owner strip configuration of every configured Application and Dynamic Feature
     * module. A later external invocation may run with Gradle configuration on demand, where the APK
     * owner is never configured and its strip task can not be read, so a normal Gradle build has to
     * publish the configuration instead of letting that invocation configure the owner.
     */
    private fun refreshNativeStripCache(projectInfo: JuggProjectInfo) {
        try {
            val entries = projectInfo.modules.values.mapNotNull { module ->
                if (module.moduleType != ModuleInfo.Type.Application &&
                        module.moduleType != ModuleInfo.Type.DynamicFeature) {
                    return@mapNotNull null
                }
                readNativeStripConfigEntry(module)
            }.distinctBy { it.moduleRootDir.absolutePath + "|" + it.variant }
            NativeStripConfigCache(juggPathManager.localClasspathStoragePathManager.nativeStripDir).write(entries)
        } catch (e: Throwable) {
            // The cache is an auxiliary capability: a failure must not break project info reading, and
            // the external invocation reports the missing configuration explicitly instead.
            println("Jugg: native strip cache refresh failed: $e")
        }
    }

    private fun readNativeStripConfigEntry(module: ModuleInfo): NativeStripConfigEntry? {
        val project = rootProject.allprojects.firstOrNull {
            it.projectDir.absoluteFile.normalize() == module.moduleRootDir.absoluteFile.normalize()
        } ?: return null
        val stripTaskName = "strip${module.buildVariant.camelCompat}DebugSymbols"
        val stripTask = project.tasks.findByName(stripTaskName) ?: run {
            println("Jugg: skip native strip cache for ${project.path}: $stripTaskName was not found")
            return null
        }
        val keepDebugSymbols = readStripKeepPatterns(stripTask)
        val stripExecutables = readStripExecutables(stripTask)
        if (keepDebugSymbols == null || stripExecutables == null) {
            println("Jugg: skip native strip cache for ${project.path}: strip configuration is unreadable")
            return null
        }
        return NativeStripConfigEntry(project.projectDir, module.buildVariant, keepDebugSymbols, stripExecutables)
    }

    /**
     * Reproduces AGP's single-file strip for one C++ build and returns the directory holding the
     * stripped `<abi>` native libraries of this invocation, which is the base directory expected by
     * the external build compiler outputs.
     *
     * Only the APK owner strip task configuration is read: the task action is never executed and its
     * input artifact provider is never resolved, so no app merge or unrelated native producer enters
     * the task graph of this invocation.
     */
    fun stripExternalNativeOutput(
        request: ExternalBuildInfoRequestItem,
        nativeOutput: File?,
        invocationOutputDir: File,
    ): File {
        println("Jugg: start stripped output for ${request.taskPath}")
        val ownerRootDir = request.apkOwnerModuleRootDir?.absoluteFile?.normalize()
        val ownerVariant = request.apkOwnerBuildVariant
        if (ownerRootDir == null || ownerVariant == null) {
            throw IllegalStateException("APK owner is missing for ${request.taskPath}, " +
                    "run a normal Gradle build to refresh Jugg project info")
        }
        if (nativeOutput == null || !nativeOutput.isDirectory) {
            throw IllegalStateException("External native output is unavailable: $nativeOutput")
        }
        val stripConfig = resolveNativeStripConfig(ownerRootDir, ownerVariant)
        val keepMatchers = stripConfig.keepDebugSymbols.map { compileKeepDebugSymbolsPattern(it) }
        val stripExecutables = stripConfig.stripExecutables

        val stripRoot = File(File(invocationOutputDir, "native"), externalNativeOutputKey(request))
        stripRoot.deleteRecursively()
        stripRoot.mkdirs()
        nativeOutput.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach { source ->
            // AGP matches keepDebugSymbols and strip tools against paths relative to the strip input.
            val keepPath = source.relativeTo(nativeOutput).path.replace(File.separatorChar, '/')
            val abi = source.parentFile.name
            val output = File(File(stripRoot, abi), source.name)
            output.parentFile.mkdirs()
            val tempOutput = File(output.parentFile, output.name + ".tmp")
            if (keepMatchers.any { it.matches(Paths.get(keepPath)) }) {
                ensureDeployableNativeSize(source, keepPath)
                copyNativeFile(source, tempOutput)
            } else {
                stripNativeFile(source, abi, stripExecutables[abi], tempOutput)
            }
            verifyStrippedNativeFile(tempOutput, keepPath)
            publishAtomically(tempOutput, output)
        }
        println("Jugg: stripped output for ${request.taskPath} into $stripRoot")
        return stripRoot
    }

    /**
     * Resolves the APK owner strip configuration. The cached configuration is preferred because this
     * invocation may run with Gradle configuration on demand, where the owner is not configured and
     * its strip task can not be read. A miss falls back to one live read, which keeps the existing
     * contract for a configured owner instead of guessing a strip tool.
     */
    private fun resolveNativeStripConfig(ownerRootDir: File, ownerVariant: String): NativeStripConfig {
        NativeStripConfigCache(juggPathManager.localClasspathStoragePathManager.nativeStripDir)
            .read(ownerRootDir, ownerVariant)?.let { return it }
        val stripTaskName = "strip${ownerVariant.camelCompat}DebugSymbols"
        val ownerProject = rootProject.allprojects.firstOrNull {
            it.projectDir.absoluteFile.normalize() == ownerRootDir
        } ?: throw IllegalStateException("APK owner project not found for $ownerRootDir")
        val stripTask = ownerProject.tasks.findByName(stripTaskName)
            ?: throw IllegalStateException("App strip task $stripTaskName was not found in " +
                    "${ownerProject.path}, run a normal Gradle build to refresh the Jugg native strip cache")
        val keepDebugSymbols = readStripKeepPatterns(stripTask)
            ?: throw IllegalStateException("App strip keepDebugSymbols is unreadable: ${stripTask.path}")
        val stripExecutables = readStripExecutables(stripTask)
            ?: throw IllegalStateException("App strip executable finder is unreadable: ${stripTask.path}")
        return NativeStripConfig(keepDebugSymbols, stripExecutables)
    }

    /** Stable directory name for one requested C++ target inside this invocation's output directory. */
    private fun externalNativeOutputKey(request: ExternalBuildInfoRequestItem): String {
        val seed = listOf(
            rootProject.rootDir.absolutePath,
            request.moduleRootDir.absolutePath,
            request.buildVariant,
            request.taskPath,
            request.apkOwnerModuleRootDir?.absolutePath.orEmpty(),
        ).joinToString("|")
        return "n" + Integer.toHexString(seed.hashCode())
    }

    /** Mirrors AGP's StripDebugSymbolsTask glob compilation for jniLibs keepDebugSymbols patterns. */
    private fun compileKeepDebugSymbolsPattern(pattern: String): java.nio.file.PathMatcher {
        val maybeSlash = if (pattern.startsWith("/") || pattern.startsWith("*")) "" else "/"
        return FileSystems.getDefault().getPathMatcher("glob:$maybeSlash$pattern")
    }

    private fun readStripKeepPatterns(stripTask: Any): List<String>? {
        val property = reflector(stripTask)["keepDebugSymbols"]?.value ?: return null
        val value = reflector(property).invoke("get")?.value ?: return null
        return (value as? Collection<*>)?.map { it.toString() } ?: emptyList()
    }

    /**
     * Reads the per-ABI strip executables through the AGP NDK handler. The tool map key changed from
     * the internal `Abi` type to the ABI string in AGP 8.11, so keys are normalized by capability
     * instead of by plugin version.
     */
    private fun readStripExecutables(stripTask: Any): Map<String, File>? {
        val ndkHandlerInput = reflector(stripTask)["ndkHandlerInput"]?.value ?: return null
        val sdkBuildService = reflector(stripTask)["sdkBuildService"]?.value?.let {
            reflector(it).invoke("get")?.value
        } ?: return null
        val ndkHandler = invokeWithArg(sdkBuildService, "versionedNdkHandler", ndkHandlerInput) ?: return null
        val finder = reflector(ndkHandler)["stripExecutableFinderProvider"]?.value?.let {
            reflector(it).invoke("get")?.value
        } ?: return null
        val executables = reflector(finder)["stripExecutables"]?.value as? Map<*, *> ?: return null
        return executables.mapNotNull { entry ->
            val abi = if (entry.key is String) entry.key as String else reflector(entry.key)["tag"]?.valueString
            val stripTool = entry.value as? File
            if (abi == null || stripTool == null) null else abi to stripTool
        }.toMap()
    }

    private fun stripNativeFile(source: File, abi: String, stripTool: File?, output: File) {
        val reason = when {
            stripTool == null -> "no strip tool for ABI '$abi'"
            !stripTool.isFile -> "strip tool $stripTool was not found"
            else -> runStripCommand(stripTool, source, output)
        }
        if (reason == null) {
            return
        }
        // Same contract as AGP: never retry the identical command, package the library as is.
        println("Jugg: $reason, package ${source.name} as is")
        output.delete()
        copyNativeFile(source, output)
    }

    /** Returns null when the library was stripped, otherwise the reason to package it as is. */
    private fun runStripCommand(stripTool: File, source: File, output: File): String? {
        val process = try {
            ProcessBuilder(
                stripTool.absolutePath, "--strip-unneeded", "-o", output.absolutePath, source.absolutePath,
            ).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return "strip ${source.name} with $stripTool failed: $e"
        }
        val detail = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            if (detail.length < STRIP_OUTPUT_LIMIT) {
                detail.append(line).append('\n')
            }
        }
        val exitCode = process.waitFor()
        return if (exitCode == 0) null else "strip ${source.name} with $stripTool returned $exitCode: $detail"
    }

    /** Streams the file, so a library larger than a JVM byte array never has to fit in memory. */
    private fun copyNativeFile(source: File, output: File) {
        output.delete()
        source.inputStream().use { input ->
            output.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
    }

    private fun verifyStrippedNativeFile(file: File, relativePath: String) {
        if (!file.isFile || !file.canRead()) {
            throw IllegalStateException("Stripped native library was not produced: $relativePath")
        }
        ensureDeployableNativeSize(file, relativePath)
    }

    /**
     * Rejects a native library that cannot be represented by the deploy data before it reaches the
     * IDE, instead of failing later with an out of memory error while reading it into a byte array.
     */
    private fun ensureDeployableNativeSize(file: File, relativePath: String) {
        val size = file.length()
        if (size <= 0L) {
            throw IllegalStateException("Native library is empty and can not be deployed: $relativePath")
        }
        if (size > Int.MAX_VALUE) {
            throw IllegalStateException("Native library $relativePath is $size bytes, exceeding the " +
                    "${Int.MAX_VALUE} bytes deploy limit. Keep debug symbols or a missing strip tool can " +
                    "cause this, run a normal Gradle build before retrying Jugg.")
        }
    }

    /**
     * Invokes one named single-argument method. `Reflector.invoke` matches parameter types exactly,
     * which fails for the AGP NDK handler argument because Gradle passes a generated implementation.
     */
    private fun invokeWithArg(value: Any, methodName: String, arg: Any): Any? {
        val method = value::class.java.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 1
        } ?: return null
        return try {
            method.isAccessible = true
            method.invoke(value, arg)
        } catch (e: Throwable) {
            println("Jugg: reflect invoke $methodName failed: $e")
            null
        }
    }

    private fun publishAtomically(tempFile: File, outputFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isExternalBuildInfoCollection(): Boolean {
        return rootProject.properties[PARAM_EXTERNAL_BUILD_REQUEST] != null &&
                rootProject.properties[PARAM_EXTERNAL_BUILD_OUTPUT] != null &&
                rootProject.properties[PARAM_EXTERNAL_BUILD_INVOCATION] != null
    }

    private fun injectApplicationAndroidTestTasks(
        requestTasks: List<String>,
        targetTasks: List<org.gradle.api.Task>,
    ) {
        rootProject.subprojects.forEach { project ->
            if (!project.plugins.hasPlugin("com.android.application")) {
                return@forEach
            }
            val variants = readApplicationVariants(project)
            val variantName = guessBuildVariant(project.toStandardModuleName(), variants, requestTasks.toSet(), requestTasks) ?: "debug"
            val testTaskName = "assemble${variantName.camelCompat}AndroidTest"
            val testTask = project.tasks.findByName(testTaskName) ?: run {
                println("Jugg: androidTest task $testTaskName not found in ${project.path}")
                return@forEach
            }
            targetTasks.forEach { task ->
                if (task != testTask) {
                    task.dependsOn(testTask)
                    println("Jugg: inject ${testTask.path} before ${task.path}")
                }
            }
        }
    }

    private fun findTasksByRequests(requestedTaskSet: Set<String>): List<org.gradle.api.Task> {
        return rootProject.allprojects.flatMap { candidateProject ->
            candidateProject.tasks.filter { task -> task.name in requestedTaskSet || task.path in requestedTaskSet }
        }
    }

    private fun readLibraryTestTasks(): List<String> {
        return rootProject.properties[PARAM_LIBRARY_TEST_TASKS]
            ?.toString()
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
    }

    private fun readApplicationVariants(project: Project): List<Variant> {
        val androidExt = try {
            reflector(project.extensions.getByName("android"))
        } catch (e: Throwable) {
            return emptyList()
        }
        val variants = mutableListOf<Variant>()
        (androidExt["applicationVariants"]?.value as? Collection<*>)?.forEach { obj ->
            val variant = reflector(obj)
            variants.add(Variant(variant["name"]?.valueString ?: return@forEach, null))
        }
        if (variants.isEmpty()) {
            variants.addAll(getCollectedAndroidVariants(rootProject, project))
        }
        return variants
    }

    /**
     * We need this to determined build variant, the info is from IDE
     */
    private fun includeAndroidTestSourceSet(): Boolean =
        rootProject.properties[PARAM_BUILD_TARGET]?.toString() == BUILD_TARGET_ANDROID_TEST

    private fun readLastProjectInfo(): JuggProjectInfoSerialize?  {
        var lastProjectInfo: File? = null

        if (juggPathManager.gradleProjectInfoFile.exists()) {
            lastProjectInfo = juggPathManager.gradleProjectInfoFile
        }
        if (lastProjectInfo == null) {
            println("Jugg: lastProjectInfo ${juggPathManager.gradleProjectInfoFile} not exists")
            return null
        }

        val lastProjectInfoSerialize = ProjectInfoSerializerInGradle(lastProjectInfo).load()
        if (lastProjectInfoSerialize == null) {
            println("Jugg: lastProjectInfo ${juggPathManager.gradleProjectInfoFile} load failed")
            return null
        }
        return lastProjectInfoSerialize
    }

    private fun writeProjectInfoFile(projectInfo: JuggProjectInfo) {
        ProjectInfoSerializerInGradle(juggPathManager.gradleProjectInfoFile).save(projectInfo)
    }

    private fun writeIncludeProjectsFile() {
        val includeProjectsFile = juggPathManager.gradleIncludeBuildsFile
        includeProjectsFile.parentFile.mkdirs()
        val projectFiles = mutableListOf<File>()
        includeBuildProjects.forEachIndexed { index, includedBuild ->
            val originFile = JuggPathManager(includedBuild.projectDir).gradleProjectInfoFile
            val targetFile = File(includeProjectsFile.parentFile, "include_build_${index + 1}_gradle_project_infos.json")
            if (!originFile.exists()) {
                println("Jugg: skip missing include build project info: $originFile")
                if (targetFile.exists()) {
                    projectFiles.add(targetFile)
                }
                return@forEachIndexed
            }
            originFile.copyTo(targetFile, true)
            projectFiles.add(targetFile)
        }
        if (projectFiles.isEmpty()) {
            includeProjectsFile.delete()
        } else {
            includeProjectsFile.writeText(projectFiles.joinToString("\n"))
        }
    }

    companion object {
        /** Retained strip process output is diagnostic only, so it stays bounded. */
        private const val STRIP_OUTPUT_LIMIT = 2000
        const val PARAM_DIFF_MODE = "jugg.diffMode"
        const val PARAM_INC_DEPLOY_TIMES = "jugg.incDeployTimes"
        const val PARAM_BUILD_TARGET = "jugg.buildTarget"
        const val PARAM_LIBRARY_TEST_TASKS = "jugg.libraryTestTasks"
        const val PARAM_EXTERNAL_BUILD_REQUEST = "jugg.externalBuildRequest"
        const val PARAM_EXTERNAL_BUILD_OUTPUT = "jugg.externalBuildOutput"
        const val PARAM_EXTERNAL_BUILD_INVOCATION = "jugg.externalBuildInvocation"
        const val BUILD_TARGET_ANDROID_TEST = "ANDROID_TEST"
        const val READ_PROJECT_INFO_TASK_NAME = "juggReadProjectInfo"
        const val READ_PROJECT_INFO_TASK_PATH = ":$READ_PROJECT_INFO_TASK_NAME"
        const val COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME = "juggCollectExternalBuildInfo"
        const val COLLECT_EXTERNAL_BUILD_INFO_TASK_PATH = ":$COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME"
    }
}
