package com.sickworm.intellij.jugg.project

import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.JuggProjectInfoSerialize
import java.io.File


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

        val gson = GsonBuilder()
            .registerTypeAdapter(File::class.java, object : TypeAdapter<File>() {
                override fun write(p0: JsonWriter?, p1: File?) {
                    p0?.value(p1?.path)
                }

                override fun read(p0: JsonReader?): File {
                    return File(p0?.nextString() ?: "")
                }

            })
            .create()
    }
}
