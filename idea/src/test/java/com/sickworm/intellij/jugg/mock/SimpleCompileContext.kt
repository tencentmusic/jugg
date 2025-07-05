package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.SigningConfig
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

    override val cmdCompileEnv: List<String> = emptyList()

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

    private fun getAndroidJarPath(moduleInfo: ModuleInfo): String {
        if (moduleInfo.compileVersion != null) {
            val androidJar = File(androidHome, "platforms/android-${moduleInfo.compileVersion}/android.jar")
            if (androidJar.exists()) {
                return androidJar.absolutePath
            }
        }

        logger.debug("android.jar not found in ${moduleInfo.name}, use ${androidJar.absolutePath} for fallback.")
        return androidJar.absolutePath
    }

    override val signingConfig: SigningConfig = SigningConfig(
        configName = "debug",
        keystore = File(System.getProperty("user.home"), ".android/debug.keystore"),
        storePassword = "android",
        keyPassword = "android",
        keyAlias = "androiddebugkey",
        storeType = "pkcs12",
        enableV1Signing = true,
        enableV2Signing = true,
        enableV3Signing = false,
        enableV4Signing = false,
        isSigningReady = true,
    )

    init {
        tempModule.buildPathInfo.moduleRootDir.clearDir()
    }

    override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> {
        val androidJar = getAndroidJarPath(moduleInfo)

        var tempDependencies: List<String> = tempModule.buildPathInfo.allClassPath.filter {
            it.exists()
        }.map {
            it.absolutePath
        }
        val tempLibraryDependency = tempModule.libraryDependencies
            .filter { it.isValid && !it.isAndroidManifest && !it.isRes }
            .map { it.file.absolutePath }
        tempDependencies = tempDependencies + tempLibraryDependency

        val classpathDependencies = moduleInfo.buildPathInfo.allClassPath.filter { file ->
            file.exists()
        }.map { file ->
            file.absolutePath
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
        val libraryDependency = moduleInfo.getLibraryDependencyPaths()

        // handles library1.commonMain only has .klib dependencies, read it in library1
        val parentLibraryModuleDependency = mutableListOf<String>()
        var parentModuleName = moduleInfo.name
        while (parentModuleName.isNotEmpty()) {
            if (!parentModuleName.contains('.')) {
                break
            }
            parentModuleName = parentModuleName.substringBeforeLast('.')
            val parentModuleInfo = modules[parentModuleName] ?: break
            if (parentModuleInfo.libraryDependencies.isEmpty()) {
                continue
            }
            logger.debug("${moduleInfo.name} found parent module $parentModuleName")
            parentLibraryModuleDependency.addAll(parentModuleInfo.getLibraryDependencyPaths())
        }

        val dependencies = mutableListOf(androidJar)
        dependencies.addAll(tempDependencies)
        dependencies.addAll(classpathDependencies)
        dependencies.addAll(moduleDependencies)
        dependencies.addAll(libraryDependency)
        dependencies.addAll(parentLibraryModuleDependency)
        dependencies.addAll(finalRFiles) // place to the last, to let R file compiled into classpathDependencies go first

        task.files.forEach {
            dependencies.addAll(it.dependencyPaths)
        }

        return dependencies
    }

    private fun ModuleInfo.getLibraryDependencyPaths(): List<String> {
        return libraryDependencies
            .filter {
                // filter unnecessary LibraryDependency for source file compilation
                val isInBuildDir = it.file.isChild(this.buildPathInfo.buildDir)
                if (isInBuildDir || it.isAndroidManifest || it.isRes || it.isKlib) {
                    return@filter false
                }
                if (!it.isValid) {
                    logger.debug("library dependency file ${it.file} not found")
                    logger.warn("library dependency [${it.name}] not found, maybe sync again helps.")
                    return@filter false
                }
                return@filter true
            }.map {
                it.file.absolutePath
            }
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

    override fun printClasspathCheck(moduleInfo: ModuleInfo) {
    }
}