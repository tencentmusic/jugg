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
            val juggProjectInfoExceptModules = juggProjectInfo.copy(modules = emptyMap())
            val modules: List<ModuleInfoSerialize> = juggProjectInfo.modules.map {
                val libraryDependencies: List<Int> = it.value.libraryDependencies.map innerMap@{ dependency ->
                    val oldIndex = dependencyIndexMap[dependency.file.absolutePath]
                    if (oldIndex != null) return@innerMap oldIndex
                    val newIndex = dependencyList.size
                    dependencyIndexMap[dependency.file.absolutePath] = newIndex
                    dependencyList.add(dependency)
                    return@innerMap newIndex
                }
                return@map ModuleInfoSerialize(it.value.copy(libraryDependencies = emptyList()), libraryDependencies)
            }
            return JuggProjectInfoSerialize(juggProjectInfoExceptModules, dependencyList, modules)
        }

        fun deserialize(projectInfoSerialize: JuggProjectInfoSerialize): JuggProjectInfo {
            val dependencyMap = mutableMapOf<Int, LibraryDependency>()
            projectInfoSerialize.dependencyList.forEachIndexed { index, libraryDependency ->
                dependencyMap[index] = libraryDependency
            }
            val modules: Map<String, ModuleInfo> = projectInfoSerialize.modules.associate { serialize ->
                val libraryDependencies = serialize.libraryDependencies.map { dependencyMap[it]!! }
                val moduleInfo = serialize.moduleInfoExceptLibraries.copy(libraryDependencies = libraryDependencies)
                return@associate moduleInfo.name to moduleInfo
            }
            val juggProjectInfo = projectInfoSerialize.juggProjectInfoExceptModules.copy(modules = modules)
            return juggProjectInfo
        }
    }

}


class ModuleInfoSerialize(
    val moduleInfoExceptLibraries: ModuleInfo,
    val libraryDependencies: List<Int>,
)