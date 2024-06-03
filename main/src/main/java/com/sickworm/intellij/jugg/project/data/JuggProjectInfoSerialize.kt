package com.sickworm.intellij.jugg.project.data

/**
 * Compress the [JuggProjectInfo] to a smaller data structure.
 */
class JuggProjectInfoSerialize(
    val juggProjectInfoExceptModules: JuggProjectInfo,
    val dependencyList: List<LibraryDependency>,
    val modules: List<ModuleInfoSerialize>,
) {


    companion object {

        fun serialize(juggProjectInfo: JuggProjectInfo): JuggProjectInfoSerialize {
            val dependencyList = mutableListOf<LibraryDependency>()
            val dependencyIndexMap = mutableMapOf<String, Int>()

            fun convertLibraryToIndexList(libraryDependencies: List<LibraryDependency>): List<Int>? {
                if (libraryDependencies.isEmpty()) return null
                return libraryDependencies.map { dependency ->
                    val oldIndex = dependencyIndexMap[dependency.file.absolutePath]
                    if (oldIndex != null) return@map oldIndex
                    val newIndex = dependencyList.size
                    dependencyIndexMap[dependency.file.absolutePath] = newIndex
                    dependencyList.add(dependency)
                    return@map newIndex
                }
            }

            val juggProjectInfoExceptModules = juggProjectInfo.copy(modules = emptyMap())
            val modules: List<ModuleInfoSerialize> = juggProjectInfo.modules.map {
                return@map ModuleInfoSerialize(it.value.copy(libraryDependencies = emptyList()),
                    libraryDependencies = convertLibraryToIndexList(it.value.libraryDependencies),
                    runtimeLibraryDependencies = convertLibraryToIndexList(it.value.runtimeLibraryDependencies),
                    annotationProcessorDependencies = convertLibraryToIndexList(it.value.annotationProcessorDependencies),
                    kaptDependencies = convertLibraryToIndexList(it.value.kaptDependencies),
                )
            }
            return JuggProjectInfoSerialize(juggProjectInfoExceptModules, dependencyList, modules)
        }

        fun deserialize(projectInfoSerialize: JuggProjectInfoSerialize): JuggProjectInfo {
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
                )
                return@associate moduleInfo.name to moduleInfo
            }
            val juggProjectInfo = projectInfoSerialize.juggProjectInfoExceptModules.copy(modules = modules)
            return juggProjectInfo
        }
    }
}


class ModuleInfoSerialize(
    val moduleInfoExceptLibraries: ModuleInfo,
    val libraryDependencies: List<Int>?,
    val runtimeLibraryDependencies: List<Int>?,
    val annotationProcessorDependencies: List<Int>?,
    val kaptDependencies: List<Int>?,
)
