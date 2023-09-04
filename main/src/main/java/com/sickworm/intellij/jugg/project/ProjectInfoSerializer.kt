package com.sickworm.intellij.jugg.project

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import java.io.File
import java.lang.reflect.Type


class ProjectInfoSerializer(private val jsonFile: File, private val logger: Logger) {

    private var memoryCache: Map<String, ModuleInfo>? = null

    @Synchronized
    fun save(modules: Map<String, ModuleInfo>) {
        val startTime = System.currentTimeMillis()

        jsonFile.parentFile?.mkdirs()
        val jsonString = Gson().toJson(modules)
        jsonFile.writeText(jsonString)
        memoryCache = modules

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("Save project info to ${jsonFile.absolutePath} cost $costTime ms")
    }

    @Synchronized
    fun load(): Map<String, ModuleInfo>? {
        if (!jsonFile.exists()) {
            return null
        }
        if (memoryCache != null) {
            return memoryCache
        }
        @Suppress("LiftReturnOrAssignment")
        try {
            val startTime = System.currentTimeMillis()
            val jsonString = jsonFile.readText()
            val type: Type = object : TypeToken<Map<String, ModuleInfo>>() {}.type
            val modules: Map<String, ModuleInfo> = Gson().fromJson(jsonString, type)
            val costTime = System.currentTimeMillis() - startTime
            logger.debug("Load project info to ${jsonFile.absolutePath} cost $costTime ms")
            memoryCache = modules
            return modules
        } catch (e: Exception) {
            logger.warn("Failed to load project info from ${jsonFile.absolutePath}", e)
            return null
        }
    }
}
