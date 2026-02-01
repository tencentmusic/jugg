@file:Suppress("MayBeConstant")

package com.sickworm.intellij.jugg.compiler.databinding

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

object DataBindingClasspathHelper {

    data class Classpath(
        val aptDependencies: List<File>,
        val kotlinPlugins: List<File>,
        val adapterJson: List<File>,
    )

    private val databindingDependencies = listOf(
        "databinding-compiler",
        "databinding-compiler-common",
        "databinding-common",
    )

    private val databindingAdapterDependency = "databinding-adapters"

    fun getClasspath(isApt: Boolean, context: ICompileContext, module: ModuleInfo, logger: Logger): Classpath {
        val modules = context.getParentModules(module, true)

        val dependencies = if (isApt) {
            modules.flatMap { it.annotationProcessorDependencies }.toMutableList()
        } else {
            modules.flatMap { it.kaptDependencies }.toMutableList()
        }

        val missingDependencies = databindingDependencies.filter { dependency ->
            return@filter dependencies.none { it.file.path.contains(dependency) }
        }
        if (missingDependencies.isNotEmpty()) {
            logger.debug("DataBindingClasspathHelper missingDependencies: $missingDependencies")
            throw IllegalStateException("DataBinding apt not found, missing dependencies: $missingDependencies. " +
                    "Fallback to gradle once may fix this issue.")
        }

        val adapterDependency = modules
            .flatMap { it.libraryDependencies }
            .find { it.isJar && it.file.path.contains(databindingAdapterDependency) }
        if (adapterDependency != null) {
            dependencies.add(adapterDependency)
        }
        // ~/.gradle/caches/transforms-3/37ceb468e4faf5883fcae514a0e5195b/transformed/databinding-adapters-7.2.2/
        // data-binding/androidx.databinding.library.baseAdapters-setter_store.json
        val adapterJson = adapterDependency?.file?.parentFile?.parentFile
            ?.resolve("data-binding")
            ?.listFiles()
            ?.filter { it.name.endsWith("-setter_store.json") }
            ?: emptyList()

        var kaptPlugin: File? = null
        if (!isApt) {
            val kotlinPlugins = modules.flatMap { it.kotlinPlugins ?: emptyList() }
            kaptPlugin = kotlinPlugins.find { it.path.contains("kotlin-annotation-processing-gradle") }
            if (kaptPlugin == null) {
                throw IllegalStateException("Kapt plugin not found, fallback to gradle once may fix this issue.")
            }
        }

        return Classpath(
            dependencies.map { it.file },
            if (kaptPlugin == null) emptyList() else listOf(kaptPlugin),
            adapterJson
        )
    }

}