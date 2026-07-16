@file:Suppress("MayBeConstant")

package com.sickworm.intellij.jugg.compiler.databinding

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * DataBindingClasspathHelper resolves and filters DataBinding/KAPT classpath inputs for incremental compilation.
 */
object DataBindingClasspathHelper {

    /**
     * Classpath carries annotation processor dependencies, Kotlin plugins, and setter stores.
     */
    data class Classpath(
        val aptDependencies: List<File>,
        val kotlinPlugins: List<File>,
        val setterStoreFiles: List<File>,
    )

    private val databindingDependencies = listOf(
        "databinding-compiler",
        "databinding-compiler-common",
        "databinding-common",
    )

    private val databindingAdapterDependency = "databinding-adapters"
    private const val annotationProcessorServicePath = "META-INF/services/javax.annotation.processing.Processor"
    private val processorJarCache = ConcurrentHashMap<String, Boolean>()

    fun getClasspath(isApt: Boolean, context: ICompileContext, module: ModuleInfo, logger: Logger): Classpath {
        val modules = context.getParentModules(module, true)

        val dependencies = if (isApt) {
            modules.flatMap { it.annotationProcessorDependencies }.toMutableList()
        } else {
            modules.flatMap { it.kaptDependencies }.toMutableList()
        }

        val adapterDependency = modules
            .flatMap { it.libraryDependencies }
            .find { it.isJar && it.file.path.contains(databindingAdapterDependency) }
        if (adapterDependency != null) {
            dependencies.add(adapterDependency)
        }
        val filteredDependencies = filterNonDataBindingAnnotationProcessor(dependencies, logger)

        val missingDependencies = databindingDependencies.filter { dependency ->
            return@filter filteredDependencies.none { it.file.path.contains(dependency) }
        }
        if (missingDependencies.isNotEmpty()) {
            logger.debug("DataBindingClasspathHelper missingDependencies: $missingDependencies")
            throw IllegalStateException("DataBinding apt not found, missing dependencies: $missingDependencies. " +
                    "Fallback to gradle once may fix this issue.")
        }
        val setterStoreFiles = findModuleSetterStores(context, modules) + findLibrarySetterStores(modules)

        var kaptPlugin: File? = null
        if (!isApt) {
            val kotlinPlugins = modules.flatMap { it.kotlinPlugins ?: emptyList() }
            kaptPlugin = kotlinPlugins.find { it.path.contains("kotlin-annotation-processing-gradle") }
            if (kaptPlugin == null) {
                throw IllegalStateException("Kapt plugin not found, fallback to gradle once may fix this issue.")
            }
        }

        return Classpath(
            filteredDependencies.map { it.file },
            if (kaptPlugin == null) emptyList() else listOf(kaptPlugin),
            setterStoreFiles.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
        )
    }

    fun getGradleModuleSetterStore(module: ModuleInfo): File? {
        return File(
            module.buildPathInfo.buildDir,
            "intermediates/data_binding_artifact/${module.buildVariant}",
        ).listFilesRecursively().filter(::isSetterStore).sortedBy { it.absolutePath }.firstOrNull()
    }

    private fun findModuleSetterStores(context: ICompileContext, modules: List<ModuleInfo>): List<File> {
        return modules.mapNotNull { module ->
            val baseline = getGradleModuleSetterStore(module) ?: return@mapNotNull null
            val argsManager = DataBindingArgsManager(context, module)
            DataBindingSetterStoreCache(argsManager.setterStoreCacheDir).getMergedStore(baseline) ?: baseline
        }
    }

    private fun findLibrarySetterStores(modules: List<ModuleInfo>): List<File> {
        return modules
            .flatMap { it.libraryDependencies }
            .flatMap { it.file.possibleArtifactRoots() }
            .distinctBy { it.absolutePath }
            .map { File(it, "data-binding") }
            .flatMap { it.listFilesRecursively() }
            .filter(::isSetterStore)
    }

    private fun File.possibleArtifactRoots(): List<File> {
        val start = if (isDirectory) this else parentFile ?: return emptyList()
        return generateSequence(start) { it.parentFile }.take(3).toList()
    }

    private fun isSetterStore(file: File): Boolean {
        return file.isFile && file.name.endsWith("-setter_store.json")
    }

    private fun filterNonDataBindingAnnotationProcessor(
        dependencies: List<LibraryDependency>,
        logger: Logger
    ): List<LibraryDependency> {
        val removedProcessorJars = mutableListOf<String>()
        return dependencies.filter { dependency ->
            val file = dependency.file
            if (!isAnnotationProcessorJar(file)) return@filter true
            val isDataBindingProcessor = databindingDependencies.any { hint -> file.path.contains(hint) }
            if (!isDataBindingProcessor) {
                removedProcessorJars.add(file.path)
            }
            isDataBindingProcessor
        }.also {
            if (removedProcessorJars.isNotEmpty()) {
                logger.debug("DataBindingClasspathHelper filtered non-databinding processors: $removedProcessorJars")
            }
        }
    }

    private fun isAnnotationProcessorJar(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.extension != "jar") {
            return false
        }
        val path = file.absolutePath
        processorJarCache[path]?.let { return it }
        val isProcessor = runCatching {
            JarFile(file).use { jar -> jar.getEntry(annotationProcessorServicePath) != null }
        }.getOrDefault(false)
        processorJarCache[path] = isProcessor
        return isProcessor
    }

}
