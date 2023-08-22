package com.sickworm.intellij.jugg.compiler

object ModuleCompileOrderUtils {

    /**
     * get module compile order by DAG
     */
    fun getModuleCompileOrders(modules: Set<ModuleInfo>): List<ModuleInfo> {
        val moduleMap = modules.associateBy { it.name }
        val dependencyMap: MutableMap<String, MutableSet<String>> = mutableMapOf()

        // Initialize dependency information
        modules.forEach { moduleInfo ->
            val dependentModules = moduleInfo.moduleDependencies.filter { dependency ->
                dependency.moduleName in moduleMap
            }
            if (dependentModules.isNotEmpty()) {
                dependencyMap[moduleInfo.name] = dependentModules.map { it.moduleName }.toMutableSet()
            }
        }

        val queue = ArrayDeque<ModuleInfo>()
        val compileOrder = mutableListOf<ModuleInfo>()

        // Add modules with no dependencies to the queue
        modules.forEach { moduleInfo ->
            if (moduleInfo.name !in dependencyMap) {
                queue.add(moduleInfo)
            }
        }

        // Start topological sorting
        while (queue.isNotEmpty()) {
            val moduleInfo = queue.removeFirst()
            compileOrder.add(moduleInfo)

            val removeKeys = mutableListOf<String>()
            dependencyMap.forEach { (moduleName, dependencies) ->
                dependencies.remove(moduleInfo.name)
                if (dependencies.isEmpty()) {
                    compileOrder.add(moduleMap[moduleName]!!)
                    removeKeys.add(moduleName)
                }
            }
            removeKeys.forEach {
                dependencyMap.remove(it)
            }
        }

        return compileOrder
    }
}