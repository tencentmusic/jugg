package com.sickworm.intellij.jugg.project.info

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import java.beans.Introspector
import java.io.File
import java.lang.reflect.Field


/**
 * ProjectInfoSerializer serializes and deserializes project models.
 */
class ProjectInfoSerializer(val dataFile: File, private val logger: Logger) {

    private var memoryCache: JuggProjectInfo? = null

    @Synchronized
    fun save(projectInfo: JuggProjectInfo?) {
        val startTime = System.currentTimeMillis()

        if (projectInfo == null) {
            memoryCache = null
            dataFile.delete()
        } else {
            dataFile.parentFile?.mkdirs()
            val juggProjectInfoSerialize = JuggProjectInfoSerialize.serialize(projectInfo)
            val serializeText = gson.toJson(juggProjectInfoSerialize)
            dataFile.writeText(serializeText)
            memoryCache = projectInfo
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Synchronized
    fun load(isSkipVersionCheck: Boolean = false): JuggProjectInfo? {
        if (!dataFile.exists()) {
            return null
        }
        if (memoryCache != null) {
            return memoryCache
        }
        @Suppress("LiftReturnOrAssignment")
        try {
            val startTime = System.currentTimeMillis()
            val dataString = dataFile.readText()
            val juggProjectInfoSerialize = gson.fromJson(dataString, JuggProjectInfoSerialize::class.java)
            val juggProjectInfo = JuggProjectInfoSerialize.deserialize(juggProjectInfoSerialize, isSkipVersionCheck)
            val costTime = System.currentTimeMillis() - startTime
            logger.debug("Load project info to ${dataFile.absolutePath} cost $costTime ms")
            memoryCache = juggProjectInfo
            return juggProjectInfo
        } catch (e: Exception) {
            logger.debug("Failed to load project info from ${dataFile.absolutePath}, $e")
            dataFile.delete()
            memoryCache = null
            return null
        }
    }

    @Synchronized
    fun clearMemoryCache() {
        memoryCache = null
    }

    companion object {

        private val fileAdapter = object : TypeAdapter<File>() {
            override fun write(p0: JsonWriter?, p1: File?) {
                p0?.value(p1?.path)
            }

            override fun read(p0: JsonReader?): File {
                return File(p0?.nextString() ?: "")
            }
        }

        // Groovy JsonGenerator writes JavaBean names such as useDataBinding.
        private val moduleInfoGson = GsonBuilder()
            .registerTypeAdapter(File::class.java, fileAdapter)
            .create()

        val gson = GsonBuilder()
            .registerTypeAdapter(File::class.java, fileAdapter)
            .registerTypeAdapter(ModuleInfo::class.java, JsonDeserializer { json, _, _ ->
                val obj = json.asJsonObject
                copyGroovyBooleanIsPropertyAliases(obj)
                moduleInfoGson.fromJson(obj, ModuleInfo::class.java)
            })
            .create()

        internal fun booleanIsPropertyFields(): List<Field> {
            return ModuleInfo::class.java.declaredFields.filter(::isBooleanIsProperty)
        }

        private fun isBooleanIsProperty(field: Field): Boolean {
            if (field.isSynthetic || !field.name.startsWith("is") || field.name.length < 3) {
                return false
            }
            return field.type == java.lang.Boolean::class.java || field.type == java.lang.Boolean.TYPE
        }

        private fun copyGroovyBooleanIsPropertyAliases(obj: JsonObject) {
            for (field in booleanIsPropertyFields()) {
                val beanName = Introspector.decapitalize(field.name.removePrefix("is"))
                if (!obj.has(field.name) && obj.has(beanName)) {
                    obj.add(field.name, obj.get(beanName))
                }
            }
        }
    }
}
