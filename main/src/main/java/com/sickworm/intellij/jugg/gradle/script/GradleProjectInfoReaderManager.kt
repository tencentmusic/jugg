package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.data.*
import org.gradle.api.Project
import org.gradle.api.initialization.IncludedBuild
import org.gradle.util.GradleVersion
import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
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

    /** Configures the invocation-scoped collector after all external build tasks are available. */
    fun configureExternalBuildInfoCollector() {
        if (!isExternalBuildInfoCollection()) {
            return
        }
        val collector = rootProject.tasks.maybeCreate(COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME)
        val localTasks = readExternalBuildInfoRequests().mapNotNull { request ->
            rootProject.allprojects.firstOrNull {
                it.projectDir.absoluteFile.normalize() == request.moduleRootDir.absoluteFile.normalize()
            }?.tasks?.findByPath(request.taskPath)
        }
        if (localTasks.isNotEmpty()) {
            collector.mustRunAfter(localTasks)
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
            )
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
