package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.run.SigningConfig
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import java.io.File

data class SimpleCompileContext(
    override val logger: Logger,
    override val tempCompileDir: File,
    override val tempModuleDir: File,
    override val androidHome: File,
    override val androidJar: File,
    override val modules: Map<String, ModuleInfo>,
    override val apkInfos: List<ApkInfo>,
    override val projectDir: File,
    override val deployedFiles: List<CompileOutput>,
) : ICompileContext {

    override val tempModule: ModuleInfo = ModuleInfo.virtualModule.copy(
        name = "temp_module",
        projectRootDir = projectDir,
        moduleRootDir = tempModuleDir,
        buildPathInfo = ModuleBuildPathInfo(projectDir, tempModuleDir, ModuleInfo.DEFAULT_BUILD_VARIANT),
    )

    override val applicationModule: ModuleInfo = modules.values.first()

    override val isEnableDesugared: Boolean = true

    override val modulesWithOrder: List<ModuleInfo> = ModuleCompileOrderUtils.getModuleCompileOrders(modules, tempModule, logger)

    private val finalRFiles: List<String> by lazy {
        return@lazy modules.mapNotNull { module ->
            val rFile = module.value.buildPathInfo.rFilePath
            if (rFile.exists()) {
                rFile.absolutePath
            } else {
                null
            }
        }
    }

    override val signingConfig: SigningConfig = SigningConfig(
        moduleName = "app",
        variantName = "debug",
        keystore = File(System.getProperty("user.home"), ".android/debug.keystore"),
        storePassword = "android",
        keyAlias = "androiddebugkey",
    )

    init {
        tempModule.buildPathInfo.moduleRootDir.clearDir()
    }

    override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> {
        val androidJar = androidJar.path

        val classpathDependencies = moduleInfo.buildPathInfo.allClassPath.filter { file ->
            file.exists()
        }.map { file ->
            file.absolutePath
        }

        val tempDependencies: List<String> = tempModule.buildPathInfo.allClassPath.filter {
            it.exists()
        }.map {
            it.absolutePath
        }

        val moduleDependencies: List<String> = moduleInfo.moduleDependencies.flatMap {
            val dependencyModuleInfo = modules[it.moduleName] ?: run {
                logger.warn("module ${it.moduleName} not found in ${moduleInfo.name}'s dependencies, maybe sync gradle again helps.")
                return@flatMap emptyList()
            }
            dependencyModuleInfo.buildPathInfo.allClassPath.filter { file ->
                file.exists()
            }.map { file ->
                file.absolutePath
            }
        }
        val libraryDependency = moduleInfo.libraryDependencies.map {
            it.file.absolutePath
        }

        if (finalRFiles.isEmpty()) {
            logger.warn("No R.jar found in project, compile may fail.")
        }

        val dependencies = mutableListOf(androidJar)
        dependencies.addAll(tempDependencies)
        dependencies.addAll(classpathDependencies)
        dependencies.addAll(moduleDependencies)
        dependencies.addAll(libraryDependency)
        dependencies.addAll(finalRFiles) // place to the last, to let R file compiled into classpathDependencies go first

        task.files.forEach {
            dependencies.addAll(it.dependencyPaths)
        }

        return dependencies
    }

    override fun getGeneratedSourcePaths(moduleInfo: ModuleInfo): List<File> {
        return emptyList()
    }

    override fun getAllDesugarClasspath(compileFiles: List<CompileFile>, moduleInfo: ModuleInfo, toDir: File) {

    }

    override fun getLastBuildAndroidManifest(file: CompileFile): File? {
        return null
    }

    override fun listenUpdate(listener: OnContextUpdate) {
    }
}