package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.android.sourceSets.SourceDirectoryModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.android.tools.idea.gradle.structure.model.helpers.androidGradlePluginVersionValues
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.guessModuleDirAdv
import com.sickworm.intellij.jugg.relativePath
import com.sickworm.intellij.jugg.toolWindow.JuggLogger
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

class CompileContextManager(
    val project: Project,
    val projectDir: String,
) {
    private val logger = JuggLogger.getInstance(project, "#Jugg-CompileContextManager")

    val rootDir = File("$projectDir/build/jugg")
    val buildDir = File(rootDir, "build")

    val tempCompileDir = File(buildDir, "compiled")
    val stagingDir = File(buildDir, "staging")
    val classPathDir = File(buildDir, "classpath")

    val libraryDir = File("$projectDir/.idea/libraries")
    var dependencies = listOf<String>()
        private set

    lateinit var compileContext: BaseCompileContext

    fun init() {
        val modules = initModuleRoots()
        initDependency(modules)
    }

    private fun initDependency(modules: Map<String, ModuleInfo>) {
        logger.debug("Start init dependency")

        // TODO auto update when file changes
        // TODO try Class.forName("com.android.tools.idea.AndroidProjectModelUtils").declaredMethods[3].invoke(Class.forName("com.android.tools.idea.AndroidProjectModelUtils"), project)
        val libDep = IntellijLibraryConfigParser(libraryDir, projectDir).parse()!!
        for (dep in libDep) {
            if (!File(dep).exists()) {
                logger.debug("Library dependency file not exists: $dep")
            }
        }

        // TODO read project settings ( ModuleRootManager.getInstance(module).sdk.rootProvider.getFiles(OrderRootType.CLASSES) )
        // TODO AndroidSdkEventListener on sdk path changed
        val androidHome = getAndroidSdkRootDir()
        logger.info("use android sdk home: $androidHome")
        if (androidHome == null) {
            throw IllegalStateException("can not found android sdk home, exit init.")
        }

        val moduleDirs = ModuleManager.getInstance(project).modules.mapNotNull {
            val baseDir = it.guessModuleDirAdv()
            if (baseDir == null) {
                logger.warn("Module $it dir not found")
                return@mapNotNull null
            }
            if (!baseDir.exists()) {
                logger.warn("Module $it dir not exist")
                return@mapNotNull null
            }
            baseDir.path
        }
        logger.debug("modules dir: ${moduleDirs.relativePath(projectDir)}")

        // TODO OPTIMIZE split by modules
        val projectDeps: List<String> = moduleDirs.flatMap { baseDir ->
            // java class path
            val deps = mutableListOf<String>()
            val buildClassPath = "${baseDir}/build/intermediates/javac/debug/classes"
            if (File(buildClassPath).exists()) {
                deps.add(buildClassPath)
            }

            // on gradle 4.1.1, R.class not storage in buildClassPath
            val rJarPath = "${baseDir}/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar"
            if (File(rJarPath).exists()) {
                deps.add(rJarPath)
            }

            // kotlin class path
            val kotlinClassPath = "${baseDir}/build/tmp/kotlin-classes/debug"
            if (File(kotlinClassPath).exists()) {
                deps.add(kotlinClassPath)
            }

            deps
        }
        for (dep in projectDeps) {
            if (!File(dep).exists()) {
                logger.debug("ProjectDep file not exists: $dep")
            }
        }

        if (!classPathDir.exists()) {
            classPathDir.mkdirs()
        }
        val juggClassPathDep = listOf(classPathDir.absolutePath)

        val context = BaseCompileContext(
            logger = JuggLogger.getInstance(project, "#Jugg-Compiler"),
            androidHome = androidHome,
            tempCompileDir = tempCompileDir,
            classPathDir = classPathDir,
            modules = modules,
        )
        logger.debug("""
            dependencies loaded:
            libDep:$libDep
            projectDep:${projectDeps.relativePath(projectDir)}
            build-tools:${context.androidBuildTools}
            android.jar:${context.androidJar}
        """.trimIndent())
        logger.info("Dependencies loaded, libDep size: ${libDep.size}, projectDep size: ${projectDeps.size}, androidDep size: 1, juggClassPathDep size: 1")

        val androidDep = context.androidJar.path
        dependencies = juggClassPathDep + projectDeps + androidDep + libDep
        compileContext = context
    }

    private fun initModuleRoots(): Map<String, ModuleInfo> {
        logger.debug("Start init module roots")

        val modules = mutableMapOf<String, ModuleInfo>()
        ModuleManager.getInstance(project).modules.forEach { module ->
            val sourceDirs = mutableListOf<File>()
            val resourceDirs = mutableListOf<File>()
            val assetDirs = mutableListOf<File>()

            val baseDir = module.guessModuleDirAdv()?.path
            if (baseDir == null) {
                logger.warn("Gradle module $module dir not found")
                return@forEach
            }

            val moduleManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(
                    JavaSourceRootType.SOURCE,
                    org.jetbrains.kotlin.config.SourceKotlinRootType
                ))
                .map { it.toIoFile() }
                .filter { !it.relativeTo(File(baseDir)).path.startsWith("build") } // ignore build source
            sourceDirs.addAll(subSourceRoots)

            val subResourceRoots = moduleManager.getSourceRoots(
                setOf(
                    JavaResourceRootType.RESOURCE,
                    org.jetbrains.kotlin.config.ResourceKotlinRootType
                ))
            subResourceRoots.forEach {
                if (it.name == "res") {
                    resourceDirs.add(it.toIoFile())
                } else if (it.name == "assets") {
                    assetDirs.add(it.toIoFile())
                }
            }
            val buildModel = ProjectBuildModel.get(project).getModuleBuildModel(module)
            if (buildModel == null) {
                logger.warn("Gradle module $module not found")
                return@forEach
            }
            val sourceSets = buildModel.android().sourceSets()

            val javaSets: List<File> = sourceSets
                .map { it.java() }
                .flatMap { it.getFileList(baseDir) }
            sourceDirs.addAll(javaSets)
            val resSets: List<File> = sourceSets
                .map { it.res() }
                .flatMap { it.getFileList(baseDir) }
            resourceDirs.addAll(resSets)
            val assetsSets: List<File> = sourceSets
                .map { it.assets() }
                .flatMap { it.getFileList(baseDir) }
            assetDirs.addAll(assetsSets)

            val buildToolsVersion: String? = buildModel.android().buildToolsVersion().readString(buildModel)
            val compileVersion: String? = buildModel.android().compileSdkVersion().readString(buildModel)

            modules[module.name] = ModuleInfo(module.name, sourceDirs, resourceDirs, assetDirs, compileVersion, buildToolsVersion)
        }

        return modules
    }

    private fun getAndroidSdkRootDir(): File? {
        val allJdks = ProjectJdkTable.getInstance().allJdks
        val allJdkString = allJdks.map {
            it.name + (": ${it.versionString}") + " (" + it.homePath + ")"
        }
        logger.debug("All available jdks: $allJdkString")
        val androidJdks = ProjectJdkTable.getInstance().allJdks.filter {
            it.name.contains("Android") && it.homeDirectory?.exists() == true
        }
        logger.debug("All available android jdks: $androidJdks")

        return androidJdks.firstOrNull()?.homeDirectory?.toIoFile()
    }

    private fun SourceDirectoryModel.getFileList(baseDir: String): List<File> {
        val dirs = srcDirs().getValue(GradlePropertyModel.LIST_TYPE)?: emptyList()
        return dirs
            .mapNotNull { it.getValue(GradlePropertyModel.STRING_TYPE) }
            .map { File(baseDir, it) }
    }

    private fun ResolvedPropertyModel.readString(model: GradleBuildModel): String? {
        var value = valueAsString()?.trim()?: return null
        // TODO better way to eval property
        if (value.contains(" as ")) {
            val index = value.indexOf(" as ")
            value = value.substring(0, index)
        }
        val property = model.inScopeProperties[value]
        if (property != null) {
            return property.valueAsString()
        }
        return value
    }
}
