package com.sickworm.intellij.jugg.project.data

import java.io.File

/**
 * Compress the [JuggProjectInfo] to a smaller data structure.
 */
class JuggProjectInfoSerialize(
    val juggProjectInfoExceptModules: JuggProjectInfo,
    val dependencyList: List<LibraryDependency>,
    val modules: List<ModuleInfoSerialize>,
    val version: Int = VERSION, // gson won't use default value if not exists, so it's ok to write it here
) {

    companion object {

        private const val VERSION = 5

        fun serialize(juggProjectInfo: JuggProjectInfo): JuggProjectInfoSerialize {
            val dependencyList = mutableListOf<LibraryDependency>()
            val dependencyIndexMap = mutableMapOf<String, Int>()

            fun convertLibraryToIndexList(libraryDependencies: List<LibraryDependency>?): List<Int>? {
                if (libraryDependencies.isNullOrEmpty()) return null
                return libraryDependencies.map { dependency ->
                    val oldIndex = dependencyIndexMap[dependency.file.absolutePath]
                    if (oldIndex != null) return@map oldIndex
                    val newIndex = dependencyList.size
                    dependencyIndexMap[dependency.file.absolutePath] = newIndex
                    dependencyList.add(dependency)
                    return@map newIndex
                }
            }

            fun convertFileToIndexList(files: List<File>?): List<Int>? {
                if (files.isNullOrEmpty()) return null
                return files.map { file ->
                    val oldIndex = dependencyIndexMap[file.absolutePath]
                    if (oldIndex != null) return@map oldIndex
                    val newIndex = dependencyList.size
                    dependencyIndexMap[file.absolutePath] = newIndex
                    dependencyList.add(LibraryDependency("standalone_file_${file.name}", file, 0L, 0L))
                    return@map newIndex
                }
            }

            val juggProjectInfoExceptModules = juggProjectInfo.copy(modules = emptyMap())
            val modules: List<ModuleInfoSerialize> = juggProjectInfo.modules.map {
                val moduleInfoSerialize = ModuleInfoSerialize(
                    it.value.copy(
                        libraryDependencies = emptyList(), runtimeLibraryDependencies = emptyList(),
                        annotationProcessorDependencies = emptyList(), kaptDependencies = emptyList(),
                        kotlinPlugins = emptyList(), kotlinExtensions = emptyList(),
                        kspDependencies = emptyList(),
                    ),
                    libraryDependencies = convertLibraryToIndexList(it.value.libraryDependencies),
                    runtimeLibraryDependencies = convertLibraryToIndexList(it.value.runtimeLibraryDependencies),
                    annotationProcessorDependencies = convertLibraryToIndexList(it.value.annotationProcessorDependencies),
                    kaptDependencies = convertLibraryToIndexList(it.value.kaptDependencies),
                    kotlinPlugins = null,
                    kotlinExtensions = null,
                    kspDependencies = convertLibraryToIndexList(it.value.kspDependencies),
                )

                // covert kotlinPlugins and kotlinExtensions at last, because it has no dependency name
                // and it may make dirty the dependencyIndexMap
                return@map moduleInfoSerialize.update(
                    kotlinPlugins = convertFileToIndexList(it.value.kotlinPlugins),
                    kotlinExtensions = convertFileToIndexList(it.value.kotlinExtensions),
                )
            }
            return JuggProjectInfoSerialize(juggProjectInfoExceptModules, dependencyList, modules)
        }

        fun deserialize(projectInfoSerialize: JuggProjectInfoSerialize, isSkipVersionCheck: Boolean = false): JuggProjectInfo {
            if (!isSkipVersionCheck && projectInfoSerialize.version != VERSION) {
                throw IllegalArgumentException("Project info too old, version=${projectInfoSerialize.version}, " +
                        "expectVersion=$VERSION. Please update your project.")
            }

            val dependencyMap = mutableMapOf<Int, LibraryDependency>()
            projectInfoSerialize.dependencyList.forEachIndexed { index, libraryDependency ->
                dependencyMap[index] = libraryDependency
            }
            val modules: Map<String, ModuleInfo> = projectInfoSerialize.modules.associate { serialize ->
                val moduleInfo = serialize.moduleInfoExceptLibraries.copy(
                    libraryDependencies = serialize.libraryDependencies?.map { dependencyMap[it]!! } ?: emptyList(),
                    runtimeLibraryDependencies = serialize.runtimeLibraryDependencies?.map { dependencyMap[it]!! } ?: emptyList(),
                    annotationProcessorDependencies = serialize.annotationProcessorDependencies?.map { dependencyMap[it]!! } ?: emptyList(),
                    kaptDependencies = serialize.kaptDependencies?.map { dependencyMap[it]!! } ?: emptyList(),
                    kotlinPlugins = serialize.kotlinPlugins?.map { dependencyMap[it]!!.file } ?: emptyList(),
                    kotlinExtensions = serialize.kotlinExtensions?.map { dependencyMap[it]!!.file } ?: emptyList(),
                    kspDependencies = serialize.kspDependencies?.map { dependencyMap[it]!! } ?: emptyList(),
                )
                return@associate moduleInfo.name to moduleInfo
            }
            val juggProjectInfo = projectInfoSerialize.juggProjectInfoExceptModules.copy(modules = modules)
            return juggProjectInfo
        }
    }
}


/**
 * ModuleInfoSerialize stores one module plus dependency-index references in serialized project snapshots.
 */
class ModuleInfoSerialize(
    val moduleInfoExceptLibraries: ModuleInfo,
    val libraryDependencies: List<Int>?,
    val runtimeLibraryDependencies: List<Int>?,
    val annotationProcessorDependencies: List<Int>?,
    val kaptDependencies: List<Int>?,
    val kotlinPlugins: List<Int>?,
    val kotlinExtensions: List<Int>?,
    val kspDependencies: List<Int>?,
) {

    fun update(kotlinPlugins: List<Int>?, kotlinExtensions: List<Int>?): ModuleInfoSerialize {
        return ModuleInfoSerialize(
            moduleInfoExceptLibraries,
            libraryDependencies, runtimeLibraryDependencies,
            annotationProcessorDependencies, kaptDependencies,
            kotlinPlugins, kotlinExtensions, kspDependencies
        )
    }
}
