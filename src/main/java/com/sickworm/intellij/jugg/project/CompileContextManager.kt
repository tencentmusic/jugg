package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.BaseCompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.getAndroidSdkRootDir
import com.sickworm.intellij.jugg.guessModuleDirAdv
import com.sickworm.intellij.jugg.relativePath
import com.sickworm.intellij.jugg.toolWindow.AidpLogger
import java.io.File

class CompileContextManager(
    val project: Project,
    val projectDir: String,
) {
    private val logger = AidpLogger.getInstance(project, "#AIDP-CompileContextManager")

    val buildDir = File("$projectDir/build/jugg/build/")

    val tempCompileDir = File(buildDir, "compiled")
    val stagingDir = File(buildDir, "staging")
    val classPathDir = File(buildDir, "classpath")

    val libraryDir = File("$projectDir/.idea/libraries")
    var dependencies = listOf<String>()
        private set

    lateinit var compileContext: BaseCompileContext

    fun init() {
        initDependency()
        initModuleRoots()
    }

    private fun initDependency() {
        logger.debug("initDependency")

        // TODO auto update when file changes
        // TODO try Class.forName("com.android.tools.idea.AndroidProjectModelUtils").declaredMethods[3].invoke(Class.forName("com.android.tools.idea.AndroidProjectModelUtils"), project)
        val libDep = IntellijLibraryConfigParser(libraryDir, projectDir).parse()!!
        for (dep in libDep) {
            if (!File(dep).exists()) {
                logger.debug("libDep file not exists: $dep")
            }
        }

        // TODO read project settings ( ModuleRootManager.getInstance(module).sdk.rootProvider.getFiles(OrderRootType.CLASSES) )
        // TODO AndroidSdkEventListener on sdk path changed
        val androidHome = getAndroidSdkRootDir(logger)
        logger.info("use android sdk home: $androidHome")
        if (androidHome == null) {
            throw IllegalStateException("can not found android sdk home, exit init.")
        }

        // TODO select sdk and build tools by gradle
        val androidDep = "$androidHome/platforms/android-30/android.jar"
        val androidBuildTools = "$androidHome/build-tools/30.0.3"
        if (!File(androidDep).exists()) {
            throw IllegalStateException("androidDep not found, path: $androidDep")
        }

        val moduleDirs = ModuleManager.getInstance(project).modules.mapNotNull {
            val baseDir = it.guessModuleDirAdv()
            if (baseDir == null) {
                logger.warn("module $it dir not found")
                return@mapNotNull null
            }
            if (!baseDir.exists()) {
                logger.warn("module $it dir not exist")
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
                logger.debug("projectDep file not exists: $dep")
            }
        }

        if (!classPathDir.exists()) {
            classPathDir.mkdirs()
        }
        val aidpClassPathDep = listOf(classPathDir.absolutePath)

        dependencies = libDep + androidDep + projectDeps + aidpClassPathDep

        logger.debug("dependencies loaded:\nlibDep: ${libDep.map { File(it).parentFile?.parentFile?.name }}\nprojectDep: ${projectDeps.relativePath(projectDir)}")
        logger.info("dependencies loaded, libDep size: ${libDep.size}, projectDep size: ${projectDeps.size}, androidDep size: 1, aidpClassPathDep size: 1")

        val context = BaseCompileContext(
            logger = AidpLogger.getInstance(project, "#AIDP-Compiler"),
            tempCompileDir = tempCompileDir,
            androidBuildTools = File(androidBuildTools),
            androidJar = File(androidDep),
            classPathDir = classPathDir
        )
        compileContext = context
    }

    private fun initModuleRoots() {
        logger.debug("initModuleRoots")

        val modules = mutableMapOf<String, ModuleInfo>()
        ModuleManager.getInstance(project).modules.forEach { module ->
            val sourceDirs = mutableListOf<File>()
            val resourceDirs = mutableListOf<File>()
            val assetDirs = mutableListOf<File>()

            val baseDir = module.guessModuleDirAdv()?.path
            if (baseDir == null) {
                logger.warn("gradle module $module dir not found")
                return@forEach
            }

            val moduleManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(
                    org.jetbrains.jps.model.java.JavaSourceRootType.SOURCE,
                    org.jetbrains.kotlin.config.SourceKotlinRootType
                ))
                .map { it.toIoFile() }
                .filter { !it.relativeTo(File(baseDir)).path.startsWith("build") } // ignore build source
            sourceDirs.addAll(subSourceRoots)

            val subResourceRoots = moduleManager.getSourceRoots(
                setOf(
                    org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE,
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
                logger.warn("gradle module $module not found")
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

            modules[module.name] = ModuleInfo(module.name, sourceDirs, resourceDirs, assetDirs)
        }
        compileContext.update(modules = modules)
    }

    private fun com.android.tools.idea.gradle.dsl.api.android.sourceSets.SourceDirectoryModel.getFileList(baseDir: String): List<File> {
        val dirs = srcDirs().getValue(com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.LIST_TYPE)?: emptyList()
        return dirs
            .mapNotNull { it.getValue(com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.STRING_TYPE) }
            .map { File(baseDir, it) }
    }

}
