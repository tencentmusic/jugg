package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.*
import groovy.json.JsonBuilder
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import java.io.File


class ProjectInfoSerializerInGradle(private val dataFile: File) {

    @Synchronized
    fun save(projectInfo: JuggProjectInfo) {
//        val startTime = System.currentTimeMillis()

        dataFile.parentFile?.mkdirs()
        val juggProjectInfoSerialize = JuggProjectInfoSerialize.serialize(projectInfo)
        val generator = getJsonGenerator()
        val builder = JsonBuilder(juggProjectInfoSerialize, generator)
        val result = builder.toString()
        dataFile.writeText(result)

//        val costTime = System.currentTimeMillis() - startTime
//        println("Jugg: Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun load(): JuggProjectInfoSerialize? {
        if (!dataFile.exists()) {
            return null
        }
        try {
//            val startTime = System.currentTimeMillis()
            val jsonSlurper = JsonSlurper()
            var juggProjectInfoSerialize: JuggProjectInfoSerialize? = null
            dataFile.inputStream().use { inputStream ->
                // we can not invoke gson in init.gradle.kts, so...
                // JsonSlurper is not a ORM tool, so we just read what we need: build variant, library dependency
                val json = jsonSlurper.parse(inputStream) as Map<String, List<Map<String, Any>>> // JuggProjectInfoSerialize
                val modules: List<ModuleInfoSerialize> = json["modules"]!!.map {
                    val module = it["moduleInfoExceptLibraries"] as Map<String, Any>
                    val moduleInfo = ModuleInfo.virtualModule.copy(
                        name = module["name"] as String,
                        buildVariant = module["buildVariant"] as String,
                        moduleRootDir = File(module["moduleRootDir"] as String),
                        projectRootDir = File(module["projectRootDir"] as String),
                        moduleType = ModuleInfo.Type.valueOf(module["moduleType"] as String),
                    )
                    ModuleInfoSerialize(
                        moduleInfo,
                        it["libraryDependencies"] as? List<Int>,
                        it["runtimeLibraryDependencies"] as? List<Int>,
                        it["annotationProcessorDependencies"] as? List<Int>,
                        it["kaptDependencies"] as? List<Int>,
                        it["kotlinPlugins"] as? List<Int>,
                        it["kotlinExtensions"] as? List<Int>,
                    )
                }
                val dependencyList = json["dependencyList"]!!.map {
                    LibraryDependency(
                        name = it["name"] as String,
                        file = File(it["file"] as String),
                        lastModifiedTime = (it["lastModifiedTime"] as Number).toLong(),
                        crc32 = (it["crc32"] as Number).toLong(), // you will get Int and Long, so convert to Number
                    )
                }
                juggProjectInfoSerialize = JuggProjectInfoSerialize(
                    juggProjectInfoExceptModules = JuggProjectInfo(modules.associate {
                        it.moduleInfoExceptLibraries.name to it.moduleInfoExceptLibraries
                    }),
                    modules = modules,
                    dependencyList = dependencyList)
            }
//            val costTime = System.currentTimeMillis() - startTime
//            println("Jugg: Load project info to ${dataFile.absolutePath} cost $costTime ms")
            return juggProjectInfoSerialize
        } catch (e: Exception) {
            println("Jugg: Failed to load project info from ${dataFile.absolutePath}, $e")
            printException(e)
            return null
        }
    }

    companion object {

        fun getJsonGenerator() : JsonGenerator {
            val fileConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == File::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    return (p0 as File).path
                }
            }
            // JsonGenerator will create "valid", "res" which are getter property, so we manually handle it there
            val libraryConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == LibraryDependency::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    val libraryDependency = p0 as? LibraryDependency ?: return "null"
                    val result = mutableMapOf<String, Any>()
                    result["name"] = libraryDependency.name
                    result["file"] = libraryDependency.file
                    result["lastModifiedTime"] = libraryDependency.lastModifiedTime
                    result["crc32"] = libraryDependency.crc32
                    return result
                }
            }
            val buildPathConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == ModuleBuildPathInfo::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    val moduleBuildPathInfo = p0 as? ModuleBuildPathInfo ?: return null
                    val result = mutableMapOf<String, Any>()
                    result["projectRootDir"] = moduleBuildPathInfo.projectRootDir
                    result["moduleRootDir"] = moduleBuildPathInfo.moduleRootDir
                    result["buildVariant"] = moduleBuildPathInfo.buildVariant
                    return result
                }
            }
            val generator = JsonGenerator.Options()
                .excludeFieldsByName("contentHash", "originalClassName")
                .excludeNulls()
                .addConverter(fileConverter)
                .addConverter(libraryConverter)
                .addConverter(buildPathConverter)
                .build()
            return generator
        }
    }
}
