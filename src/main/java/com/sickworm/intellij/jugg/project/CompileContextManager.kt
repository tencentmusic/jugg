package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.android.sourceSets.SourceDirectoryModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.deploy.ApkParser
import com.sickworm.intellij.jugg.compiler.guessModuleDirAdv
import com.sickworm.intellij.jugg.compiler.relativePath
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

/**
 * Manage context for JuggCompiler.
 * Do:
 * 1. read project structure
 * 2. read android sdk information
 * 3. parse apk
 * 4. generate class path
 * ...
 */
class CompileContextManager(
    private val project: Project,
    private val projectDir: String,
    private val moduleManager: ModuleManager = ModuleManager.getInstance(project), // mock
    private val projectJdkTable: ProjectJdkTable = ProjectJdkTable.getInstance(), // mock
    private val projectBuildModel: ProjectBuildModel = ProjectBuildModel.get(project), // mock
) {
    private val logger = JuggLogger.getInstance(project, "#Jugg-CompileContextManager")

    private val juggRootDir = File("$projectDir/build/jugg")
    private val compileRootDir = File(juggRootDir, "build")

    val stagingDir = File(compileRootDir, "staging")
    private val tempCompileDir = File(compileRootDir, "compiled")
    private val fullBuildClassPathDir = File(compileRootDir, "classpath_full")
    private val incBuildClassPathDir = File(compileRootDir, "classpath_inc")

    private val libraryDir = File("$projectDir/.idea/libraries")
    /**
     * contains all dependencies in an Android project.
     * TODO more efficient
     */
    var dependencies = listOf<String>()
        private set

    val compileContext: BaseCompileContext
        get() {
            return compileContextInside?: throw JuggInternalException.compilerContextNotInit()
        }

    private var compileContextInside: BaseCompileContext? = null

    fun initProjectInfo() {
        val modules = initModules()
        initContext(modules)
    }

    fun initFullBuildInfo(apks: List<ApkInfo>) {
        val startTime = System.currentTimeMillis()
        val parsedApks = apks.map {
            ApkParser().parse(it, isSkipCode = true)
        }
        val parsedApksEndTime = System.currentTimeMillis()
        logger.debug("initFullBuildInfo parsedApks cost ${parsedApksEndTime - startTime}")

        // TODO reopen
        // close for now for better performance
//        // something wrong with this... use build class path for now
//        parsedApks.forEach { apk ->
//            apk.classes.values.forEach { classNode ->
//                val bytes = classNode.dumpClassStub()
//                val outputPath = classNode.className.replace('.', '/') + ".class"
//                val outputFile = File(fullBuildClassPathDir, outputPath)
//                if (outputFile.exists()) {
//                    outputFile.delete()
//                }
//                outputFile.parentFile?.mkdirs()
//                outputFile.writeBytes(bytes)
//            }
//        }
        val buildClassPathEndTime = System.currentTimeMillis()
        logger.debug("initFullBuildInfo dumpClassStub cost ${buildClassPathEndTime - parsedApksEndTime}")

        compileContext.update(parsedApks = parsedApks)

        val updateEndTime = System.currentTimeMillis()
        logger.debug("initFullBuildInfo compileContext.update cost ${updateEndTime - buildClassPathEndTime}")

        updateProjectDependencies()

        val updateDepEndTime = System.currentTimeMillis()
        logger.debug("initFullBuildInfo updateProjectDependencies cost ${updateDepEndTime - buildClassPathEndTime}")
    }

    private fun initContext(modules: Map<String, ModuleInfo>) {
        logger.debug("Start initContext")

        val androidHome = getAndroidSdkRootDir()
        logger.info("use android sdk home: $androidHome")
        if (androidHome == null) {
            throw JuggException.androidHomeNotFound()
        }

        val context = BaseCompileContext(
            logger = JuggLogger.getInstance(project, "#Jugg-Compiler"),
            androidHome = androidHome,
            tempCompileDir = tempCompileDir,
            classPathDir = incBuildClassPathDir,
            modules = modules,
        )
        logger.debug("""
            context loaded:
            build-tools:${context.androidBuildTools}
            android.jar:${context.androidJar}
        """.trimIndent())

        compileContextInside = context
    }

    private fun updateProjectDependencies() {

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
            throw JuggException.androidHomeNotFound()
        }

        val moduleDirs = compileContext.modules.values.map { it.rootDir }
        logger.debug("modules dir: ${moduleDirs.relativePath(projectDir)}")

        // TODO remove this after enable apk class dump, or we need focus on build dir changed / deleted
        val projectDeps: List<File> = compileContext.modules.values.flatMap { module ->
            module.buildPathInfo.allClassPath
                .filter { it.exists() }
        }
        for (dep in projectDeps) {
            if (!dep.exists()) {
                logger.debug("ProjectDep file not exists: $dep")
            }
        }
        val projectDepStrings = projectDeps.map { it.path }

        if (!incBuildClassPathDir.exists()) {
            incBuildClassPathDir.mkdirs()
        }
        if (!fullBuildClassPathDir.exists()) {
            fullBuildClassPathDir.mkdirs()
        }
//        val juggClassPathDep = listOf(fullBuildClassPathDir.absolutePath, incBuildClassPathDir.absolutePath)
        val juggClassPathDep = listOf<String>(incBuildClassPathDir.absolutePath)

        val androidDep = compileContext.androidJar.path
        dependencies = juggClassPathDep + projectDepStrings + androidDep + libDep

        logger.debug("""
            Dependencies loaded:
            libDep:$libDep
            projectDep:${projectDeps.relativePath(projectDir)}
        """.trimIndent())
        logger.info("Dependencies loaded, libDep size: ${libDep.size}, projectDep size: ${projectDeps.size}, androidDep size: 1, juggClassPathDep size: 2")

    }

    private fun initModules(): Map<String, ModuleInfo> {
        logger.debug("Start init module roots")

        val modules = mutableMapOf<String, ModuleInfo>()
        moduleManager.modules.forEach { module ->
            val sourceDirs = mutableListOf<File>()
            val resourceDirs = mutableListOf<File>()
            val assetDirs = mutableListOf<File>()

            val baseDir = module.guessModuleDirAdv()
            if (baseDir == null) {
                logger.warn("Gradle module $module dir not found")
                return@forEach
            }

            val moduleManager = ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(
                    JavaSourceRootType.SOURCE,
                    org.jetbrains.kotlin.config.SourceKotlinRootType
                ))
                .map { it.toIoFile() }
                .filter { !it.relativeTo(baseDir).path.startsWith("build") } // ignore build source
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
            val buildModel = projectBuildModel.getModuleBuildModel(module)
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

            modules[module.name] = ModuleInfo(
                module.name, baseDir, sourceDirs, resourceDirs, assetDirs,
                compileVersion, buildToolsVersion)
        }

        return modules
    }

    private fun getAndroidSdkRootDir(): File? {
        val allJdks = projectJdkTable.allJdks
        val allJdkString = allJdks.map {
            it.name + (": ${it.versionString}") + " (" + it.homePath + ")"
        }
        logger.debug("All available jdks: $allJdkString")
        val androidJdks = allJdks.filter {
            it.name.contains("Android") && it.homeDirectory?.exists() == true
        }
        logger.debug("All available android jdks: $androidJdks")

        return androidJdks.firstOrNull()?.homeDirectory?.toIoFile()
    }

    private fun SourceDirectoryModel.getFileList(baseDir: File): List<File> {
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
        // TODO try model.ext() ?
        val property = model.inScopeProperties[value]
        if (property != null) {
            return property.valueAsString()
        }
        return value
    }
}
