package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.*
import groovy.json.JsonBuilder
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import java.io.File


class ProjectInfoSerializerInGradle(private val dataFile: File, private val logger: (String) -> Unit) {

    @Synchronized
    fun save(projectInfo: JuggProjectInfo) {
        val startTime = System.currentTimeMillis()

        dataFile.parentFile?.mkdirs()
        val juggProjectInfoSerialize = JuggProjectInfoSerialize.serialize(projectInfo)

        val fileConverter = object : JsonGenerator.Converter {
            override fun handles(p0: Class<*>?): Boolean {
                return p0 == File::class.java
            }

            override fun convert(p0: Any?, p1: String?): Any {
                return (p0 as File).absolutePath
            }
        }
        val generator = JsonGenerator.Options()
            .excludeFieldsByName("contentHash", "originalClassName")
            .addConverter(fileConverter)
            .build()
        val builder = JsonBuilder(juggProjectInfoSerialize, generator)
        val result = builder.toString()
        dataFile.writeText(result)

        val costTime = System.currentTimeMillis() - startTime
        logger("Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun load(): JuggProjectInfoSerialize? {
        if (!dataFile.exists()) {
            return null
        }
        try {
            val startTime = System.currentTimeMillis()
            val jsonSlurper = JsonSlurper()
            var juggProjectInfoSerialize: JuggProjectInfoSerialize? = null
            dataFile.inputStream().use { inputStream ->
                // we can not invoke gson in init.gradle.kts, so...
                // JsonSlurper is not a ORM tool, so we just read what we need: build variant, library dependency
                val json = jsonSlurper.parse(inputStream) as Map<String, List<Map<String, Any>>> // JuggProjectInfoSerialize
                val modules = json["modules"]!!.map {
                    val module = it["moduleInfoExceptLibraries"] as Map<String, Any>
                    ModuleInfo.virtualModule.copy(
                        name = module["name"] as String,
                        buildVariant = module["buildVariant"] as String,
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
                    juggProjectInfoExceptModules = JuggProjectInfo(modules.associateBy { it.name }),
                    modules = modules.map {
                        ModuleInfoSerialize(it, null,null,null,null)
                    },
                    dependencyList = dependencyList)
            }
            val costTime = System.currentTimeMillis() - startTime
            logger("Load project info to ${dataFile.absolutePath} cost $costTime ms")
            return juggProjectInfoSerialize
        } catch (e: Exception) {
            logger("Failed to load project info from ${dataFile.absolutePath}, $e")
            printException(e)
            return null
        }
    }
}
