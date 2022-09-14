package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.android.sourceSets.SourceDirectoryModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.compiler.guessModuleDirAdv
import com.sickworm.intellij.jugg.compiler.relativePath
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File
import kotlin.system.measureTimeMillis

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
    private val pathManager: JuggPathManager,
    private val moduleManager: ModuleManager = ModuleManager.getInstance(project), // mock
    private val projectJdkTable: ProjectJdkTable = ProjectJdkTable.getInstance(), // mock
    private val projectBuildModel: ProjectBuildModel = ProjectBuildModel.get(project), // mock,
    private val logger: Logger = JuggLogger.getInstance(project, "CompileContextManager"),
) {

    val stagingDir = File(pathManager.compileRootDir, "staging")
    private val tempCompileDir = File(pathManager.compileRootDir, "compiled")

    /**
     * contains all dependencies in an Android project.
     * TODO more efficient
     */
    var dependencies = listOf<String>()
        private set

    /** Init after [initProjectInfo] */
    val compileContext: BaseCompileContext
        get() {
            return compileContextInside?: throw JuggInternalException.compilerContextNotInit()
        }

    /** Init after [initProjectInfo] */
    private var compileContextInside: BaseCompileContext? = null

    fun initProjectInfo() {
        val costTime = measureTimeMillis {
            val modules = initModules()
            initContext(modules)
        }
        logger.debug("initProjectInfo cost ${costTime}ms")
    }

    fun initFullBuildInfo(compileContextInfo: CompileContextInfo) {
        // need re init project info after full compile
        initProjectInfo()

        val startTime = System.currentTimeMillis()
        updateProjectDependencies(compileContextInfo)
        val endTime = System.currentTimeMillis()
        logger.debug("initFullBuildInfo cost ${endTime - startTime}ms")
    }

    private fun initContext(modules: Map<String, ModuleInfo>) {
        logger.debug("Start initContext")

        // TODO read project settings ( ModuleRootManager.getInstance(module).sdk.rootProvider.getFiles(OrderRootType.CLASSES) )
        // TODO AndroidSdkEventListener on sdk path changed
        val androidHome = getAndroidSdkRootDir()
        logger.debug("Use android sdk home: $androidHome")
        if (androidHome == null) {
            throw JuggException.androidHomeNotFound()
        }

        val context = BaseCompileContext(
            logger = JuggLogger.getInstance(project, "Compiler"),
            androidHome = androidHome,
            tempCompileDir = tempCompileDir,
            modules = modules,
            minApi = JuggSettings.minApi
        )
        logger.debug("""
            context loaded:
            build-tools:${context.androidBuildTools}
            android.jar:${context.androidJar}
        """.trimIndent())

        compileContextInside = context
    }

    private fun updateProjectDependencies(compileContextInfo: CompileContextInfo) {
        val copyModules = compileContext.modules.map { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name]
            if (newBuildPathInfo != null) {
                name to module.copy(buildPathInfo = newBuildPathInfo)
            } else {
                // module that without build path. e.g. root project
                name to module
            }
        }.toMap()
        compileContext.update(apkInfos = compileContextInfo.apkInfos, modules = copyModules)

        val thirdPartyDependencies = compileContextInfo.thirdPartyDependencies

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

        val androidDep = compileContext.androidJar.path
        dependencies = projectDepStrings + androidDep + thirdPartyDependencies

        logger.debug("""
            Dependencies loaded:
            libDep:$thirdPartyDependencies
            projectDep:${projectDeps.relativePath(pathManager.projectDir)}
        """.trimIndent())
        logger.info("Dependencies loaded, " +
                "libDep size: ${thirdPartyDependencies.size}, " +
                "projectDep size: ${projectDeps.size}"
        )
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
                logger.debug("$module is not a gradle module, ignore")
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
            val kotlinJvmTarget: String? = buildModel.android().kotlinOptions().jvmTarget()
                .toLanguageLevel()?.toJavaVersion()?.toString()
            val javaSourceCompatibility: String? = buildModel.android().compileOptions().sourceCompatibility()
                .toLanguageLevel()?.toJavaVersion()?.toString()
            val javaTargetCompatibility: String? = buildModel.android().compileOptions().targetCompatibility()
                .toLanguageLevel()?.toJavaVersion()?.toString()

            modules[module.name] = ModuleInfo(
                module.name, baseDir, sourceDirs, resourceDirs, assetDirs,
                compileVersion, buildToolsVersion,
                kotlinJvmTarget, javaSourceCompatibility, javaTargetCompatibility,
                ModuleBuildPathInfo.fromModule(baseDir),
            )
        }

        val moduleDirs = modules.values.map { it.rootDir }
        logger.debug("modules dir: ${moduleDirs.relativePath(pathManager.projectDir)}")

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
