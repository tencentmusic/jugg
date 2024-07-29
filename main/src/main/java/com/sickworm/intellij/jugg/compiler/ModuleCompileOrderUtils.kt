package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.data.ModuleInfo

object ModuleCompileOrderUtils {

    fun getModuleCompileOrders(modules: Map<String, ModuleInfo>, tempModule: ModuleInfo, logger: Logger): List<ModuleInfo> {
        val orders = getModuleCompileOrders(modules.values.toMutableSet() + tempModule)
        logger.debug("Find compile order in:  module size: ${modules.size}, modules: ${modules.map { it.value.name }}")
        logger.debug("Find compile order out: module size: ${orders.size}, modules : ${orders.map { it.name }}")
        return orders
    }

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
                    queue.add(moduleMap[moduleName]!!)
                    removeKeys.add(moduleName)
                }
            }
            removeKeys.forEach {
                dependencyMap.remove(it)
            }
        }

        if (dependencyMap.isNotEmpty()) {
            // Oops, there must have circular dependencies
            // Add it to the end of compile order. It's not a good idea, but it's better than lost.
            val remainModules = dependencyMap.entries
                .sortedBy { it.value.size }
                .map { moduleMap[it.key]!! }
            compileOrder.addAll(remainModules)
        }

        return compileOrder
    }
}