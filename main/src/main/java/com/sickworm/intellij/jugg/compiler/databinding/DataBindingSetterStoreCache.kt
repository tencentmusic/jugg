package com.sickworm.intellij.jugg.compiler.databinding

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Merges the current module store produced by DataBinding APT into a persistent module store.
 */
class DataBindingSetterStoreCache(private val cacheDir: File) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val generationsDir = File(cacheDir, "generations")
    private val currentFile = File(cacheDir, "current")

    /**
     * Replaces declarations emitted by the current processor invocation and publishes a new merged store.
     */
    fun merge(baselineStore: File, generatedStore: File): File {
        val baselineHash = baselineStore.sha256()
        val generated = readStore(generatedStore)
        validateStore(readStore(baselineStore))
        validateStore(generated)

        val previousStore = getMergedStore(baselineStore) ?: baselineStore
        val previous = readStore(previousStore)
        validateStore(previous)
        val merged = previous.deepCopy()
        removeDeclaringTypes(merged, extractDeclaringTypes(generated))
        mergeStore(merged, generated)

        val generation = File(generationsDir, UUID.randomUUID().toString())
        val mergedDir = File(generation, MERGED_DIR).apply { mkdirs() }
        File(generation, BASELINE_HASH_FILE).writeText(baselineHash)
        val mergedFile = File(mergedDir, baselineStore.name)
        mergedFile.writeText(gson.toJson(merged))
        validateStore(readStore(mergedFile))
        publish(generation)
        return mergedFile
    }

    fun getMergedStore(baselineStore: File): File? {
        val generation = currentGeneration(baselineStore.sha256()) ?: return null
        return File(generation, "$MERGED_DIR/${baselineStore.name}").takeIf { it.isFile }
    }

    private fun currentGeneration(baselineHash: String): File? {
        if (!currentFile.isFile) return null
        val generation = File(generationsDir, currentFile.readText().trim())
        val storedHash = File(generation, BASELINE_HASH_FILE).takeIf { it.isFile }?.readText()?.trim()
        return generation.takeIf { it.isDirectory && storedHash == baselineHash }
    }

    private fun publish(generation: File) {
        cacheDir.mkdirs()
        val tempPointer = File(cacheDir, "current.tmp")
        tempPointer.writeText(generation.name)
        runCatching {
            Files.move(
                tempPointer.toPath(),
                currentFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(tempPointer.toPath(), currentFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        generationsDir.listFiles()
            ?.filter { it != generation }
            ?.forEach { it.deleteRecursively() }
    }

    private fun validateStore(store: JsonObject) {
        check(store["version"]?.asInt == SUPPORTED_VERSION) {
            "Unsupported DataBinding setter store version: ${store["version"]}"
        }
        STORE_FIELDS.forEach { field ->
            check(store.has(field)) { "DataBinding setter store field missing: $field" }
        }
    }

    private fun removeDeclaringTypes(store: JsonObject, removedTypes: Set<String>) {
        removeFromNestedComplexMaps(store, "adapterMethods", removedTypes)
        removeFromNestedObjects(store, "renamedMethods", removedTypes)
        removeFromNestedObjects(store, "conversionMethods", removedTypes)
        removeFromSimpleObject(store, "untaggableTypes", removedTypes)
        removeFromComplexMap(store, "multiValueAdapters", removedTypes, typeInValue = true)
        removeFromNestedComplexMaps(store, "inverseAdapters", removedTypes)
        removeFromNestedObjects(store, "inverseMethods", removedTypes)
        removeFromComplexMap(store, "twoWayMethods", removedTypes, typeInValue = false)
    }

    private fun extractDeclaringTypes(store: JsonObject): Set<String> {
        val result = linkedSetOf<String>()
        collectNestedComplexMapTypes(store, "adapterMethods", result)
        collectNestedObjectTypes(store, "renamedMethods", result)
        collectNestedObjectTypes(store, "conversionMethods", result)
        store["untaggableTypes"].asJsonObject.entrySet().forEach { result += it.value.asString }
        collectComplexMapTypes(store, "multiValueAdapters", result, typeInValue = true)
        collectNestedComplexMapTypes(store, "inverseAdapters", result)
        collectNestedObjectTypes(store, "inverseMethods", result)
        collectComplexMapTypes(store, "twoWayMethods", result, typeInValue = false)
        return result
    }

    private fun mergeStore(target: JsonObject, source: JsonObject) {
        check(target["version"] == source["version"] && target["useAndroidX"] == source["useAndroidX"]) {
            "Incompatible DataBinding setter store"
        }
        mergeNestedComplexMaps(target, source, "adapterMethods")
        mergeNestedObjects(target, source, "renamedMethods")
        mergeNestedObjects(target, source, "conversionMethods")
        mergeSimpleObject(target, source, "untaggableTypes")
        mergeComplexMap(target, source, "multiValueAdapters")
        mergeNestedComplexMaps(target, source, "inverseAdapters")
        mergeNestedObjects(target, source, "inverseMethods")
        mergeComplexMap(target, source, "twoWayMethods")
    }

    private fun mergeNestedComplexMaps(target: JsonObject, source: JsonObject, field: String) {
        val targetOuter = target[field].asJsonObject
        source[field].asJsonObject.entrySet().forEach { (outerKey, sourceEntries) ->
            val targetEntries = targetOuter.getAsJsonArray(outerKey) ?: JsonArray().also { targetOuter.add(outerKey, it) }
            mergeComplexEntries(targetEntries, sourceEntries.asJsonArray, "$field.$outerKey")
        }
    }

    private fun mergeNestedObjects(target: JsonObject, source: JsonObject, field: String) {
        val targetOuter = target[field].asJsonObject
        source[field].asJsonObject.entrySet().forEach { (outerKey, sourceEntries) ->
            val targetEntries = targetOuter.getAsJsonObject(outerKey) ?: JsonObject().also { targetOuter.add(outerKey, it) }
            sourceEntries.asJsonObject.entrySet().forEach { (key, value) ->
                putOrCheck(targetEntries, key, value, "$field.$outerKey.$key")
            }
        }
    }

    private fun mergeSimpleObject(target: JsonObject, source: JsonObject, field: String) {
        val targetObject = target[field].asJsonObject
        source[field].asJsonObject.entrySet().forEach { (key, value) ->
            putOrCheck(targetObject, key, value, "$field.$key")
        }
    }

    private fun mergeComplexMap(target: JsonObject, source: JsonObject, field: String) {
        val sourceEntries = source[field]
        if (sourceEntries.isJsonObject && sourceEntries.asJsonObject.size() == 0) return
        val targetEntries = target[field]
        if (targetEntries.isJsonObject && targetEntries.asJsonObject.size() == 0) {
            target.add(field, sourceEntries.deepCopy())
            return
        }
        check(targetEntries.isJsonArray && sourceEntries.isJsonArray) {
            "Incompatible DataBinding setter store field: $field"
        }
        mergeComplexEntries(targetEntries.asJsonArray, sourceEntries.asJsonArray, field)
    }

    private fun mergeComplexEntries(target: JsonArray, source: JsonArray, path: String) {
        source.forEach { sourceEntry ->
            val pair = sourceEntry.asJsonArray
            val key = pair[0]
            val existing = target.firstOrNull { it.asJsonArray[0] == key }
            if (existing == null) {
                target.add(sourceEntry.deepCopy())
            } else {
                check(existing.asJsonArray[1] == pair[1]) { "Conflicting DataBinding setter store entry: $path" }
            }
        }
    }

    private fun putOrCheck(target: JsonObject, key: String, value: JsonElement, path: String) {
        val existing = target[key]
        if (existing == null) {
            target.add(key, value.deepCopy())
        } else {
            check(existing == value) { "Conflicting DataBinding setter store entry: $path" }
        }
    }

    private fun removeFromNestedComplexMaps(store: JsonObject, field: String, removedTypes: Set<String>) {
        val outer = store[field].asJsonObject
        outer.entrySet().toList().forEach { (key, entries) ->
            removeFromJsonArray(entries.asJsonArray) { declaringType(it.asJsonArray[1]) in removedTypes }
            if (entries.asJsonArray.size() == 0) outer.remove(key)
        }
    }

    private fun removeFromNestedObjects(store: JsonObject, field: String, removedTypes: Set<String>) {
        val outer = store[field].asJsonObject
        outer.entrySet().toList().forEach { (key, entries) ->
            entries.asJsonObject.entrySet().toList().forEach { (innerKey, value) ->
                if (declaringType(value) in removedTypes) entries.asJsonObject.remove(innerKey)
            }
            if (entries.asJsonObject.size() == 0) outer.remove(key)
        }
    }

    private fun removeFromSimpleObject(store: JsonObject, field: String, removedTypes: Set<String>) {
        val values = store[field].asJsonObject
        values.entrySet().toList().forEach { (key, value) ->
            if (value.asString in removedTypes) values.remove(key)
        }
    }

    private fun removeFromComplexMap(store: JsonObject, field: String, removedTypes: Set<String>, typeInValue: Boolean) {
        val entries = store[field]
        if (entries.isJsonObject && entries.asJsonObject.size() == 0) return
        check(entries.isJsonArray) { "Unsupported DataBinding setter store field: $field" }
        removeFromJsonArray(entries.asJsonArray) { entry ->
            val pair = entry.asJsonArray
            declaringType(pair[if (typeInValue) 1 else 0]) in removedTypes
        }
    }

    private fun collectNestedComplexMapTypes(store: JsonObject, field: String, result: MutableSet<String>) {
        store[field].asJsonObject.entrySet().forEach { (_, entries) ->
            entries.asJsonArray.forEach { declaringType(it.asJsonArray[1])?.let(result::add) }
        }
    }

    private fun collectNestedObjectTypes(store: JsonObject, field: String, result: MutableSet<String>) {
        store[field].asJsonObject.entrySet().forEach { (_, entries) ->
            entries.asJsonObject.entrySet().forEach { (_, value) -> declaringType(value)?.let(result::add) }
        }
    }

    private fun collectComplexMapTypes(store: JsonObject, field: String, result: MutableSet<String>, typeInValue: Boolean) {
        val entries = store[field]
        if (entries.isJsonObject && entries.asJsonObject.size() == 0) return
        check(entries.isJsonArray) { "Unsupported DataBinding setter store field: $field" }
        entries.asJsonArray.forEach { entry ->
            declaringType(entry.asJsonArray[if (typeInValue) 1 else 0])?.let(result::add)
        }
    }

    private fun declaringType(element: JsonElement): String? {
        return element.takeIf { it.isJsonObject }?.asJsonObject?.get("type")?.asString
    }

    private fun removeFromJsonArray(array: JsonArray, predicate: (JsonElement) -> Boolean) {
        for (index in array.size() - 1 downTo 0) {
            if (predicate(array[index])) array.remove(index)
        }
    }

    private fun readStore(file: File): JsonObject {
        check(file.isFile) { "DataBinding setter store not found: $file" }
        return JsonParser.parseString(file.readText()).asJsonObject
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SUPPORTED_VERSION = 5
        const val BASELINE_HASH_FILE = "baseline.sha256"
        const val MERGED_DIR = "merged"
        val STORE_FIELDS = listOf(
            "adapterMethods",
            "renamedMethods",
            "conversionMethods",
            "untaggableTypes",
            "multiValueAdapters",
            "inverseAdapters",
            "inverseMethods",
            "twoWayMethods",
            "useAndroidX",
        )
    }
}
