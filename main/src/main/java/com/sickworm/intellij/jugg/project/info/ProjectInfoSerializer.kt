package com.sickworm.intellij.jugg.project.info

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import java.beans.Introspector
import java.io.File
import java.lang.reflect.Field
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption


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
            val tempFile = File.createTempFile(dataFile.name + ".", ".tmp", dataFile.absoluteFile.parentFile)
            try {
                tempFile.writeText(serializeText)
                try {
                    Files.move(
                        tempFile.toPath(),
                        dataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tempFile.delete()
            }
            memoryCache = projectInfo
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Synchronized
    fun load(isSkipVersionCheck: Boolean = false): JuggProjectInfo? {
        return loadInternal(isSkipVersionCheck, deleteOnFailure = true)
    }

    /** Loads a snapshot for a targeted merge without deleting the authoritative file on parse failure. */
    @Synchronized
    fun loadPreservingFile(isSkipVersionCheck: Boolean = false): JuggProjectInfo? {
        return loadInternal(isSkipVersionCheck, deleteOnFailure = false)
    }

    private fun loadInternal(isSkipVersionCheck: Boolean, deleteOnFailure: Boolean): JuggProjectInfo? {
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
            if (deleteOnFailure) {
                dataFile.delete()
            }
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
                restoreExternalBuildOutputs(obj)
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

        /**
         * Restores the unified external build outputs from snapshots written before they were unified:
         * legacy Flutter kept the assets directory in `outputDir` and the native output in
         * `nativeLibsArchive` (a Jar) or `nativeLibsDir` (a jniLibs directory); legacy C++ kept its
         * native output in `outputDir`.
         */
        private fun restoreExternalBuildOutputs(moduleObj: JsonObject) {
            val buildInfos = moduleObj.getAsJsonArray("externalBuildInfos") ?: return
            buildInfos.forEach { element ->
                val info = element.asJsonObject
                validateExternalBuildInputDirs(info)
                restoreExternalBuildDefaults(info)
                val isFlutter = info.stringOrNull("type") == ExternalBuildType.Flutter.name
                if (!info.has("assetsOutputDir") && isFlutter) {
                    info.stringOrNull("outputDir")?.let { info.addProperty("assetsOutputDir", it) }
                }
                if (info.has("nativeOutput")) {
                    return@forEach
                }
                val nativeOutput = if (isFlutter) {
                    info.stringOrNull("nativeLibsArchive") ?: info.stringOrNull("nativeLibsDir")
                } else {
                    info.stringOrNull("outputDir")
                }
                nativeOutput?.let { info.addProperty("nativeOutput", it) }
            }
        }

        private fun validateExternalBuildInputDirs(info: JsonObject) {
            val inputDirs = info.getAsJsonArray("inputDirs")
                ?: throw IllegalStateException(EXTERNAL_BUILD_INPUT_SCHEMA_ERROR)
            inputDirs.forEach { element ->
                if (!element.isJsonObject) {
                    throw IllegalStateException(EXTERNAL_BUILD_INPUT_SCHEMA_ERROR)
                }
            }
        }

        private fun restoreExternalBuildDefaults(info: JsonObject) {
            listOf("configFiles", "excludedDirs").forEach { name ->
                if (!info.has(name)) {
                    info.add(name, JsonArray())
                }
            }
        }

        private fun JsonObject.stringOrNull(name: String): String? {
            val value = get(name) ?: return null
            return if (value.isJsonPrimitive) value.asString else null
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
