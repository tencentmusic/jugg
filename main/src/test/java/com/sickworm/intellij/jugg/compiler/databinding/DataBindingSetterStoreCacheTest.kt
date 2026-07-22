package com.sickworm.intellij.jugg.compiler.databinding

import com.google.gson.JsonParser
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DataBindingSetterStoreCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `generated store should preserve baseline entries`() {
        val baseline = store(
            "baseline.json",
            adapter("android:visibility", "android.view.View", "boolean", "demo.ExistingAdapters"),
        )
        val generated = store(
            "new.json",
            adapter("juggVisible", "android.view.View", "boolean", "demo.NewAdapters"),
        )
        val cache = DataBindingSetterStoreCache(temporaryFolder.newFolder("cache"))

        val merged = cache.merge(baseline, generated)

        assertEquals(listOf("demo.ExistingAdapters"), adapterTypes(merged, "android:visibility"))
        assertEquals(listOf("demo.NewAdapters"), adapterTypes(merged, "juggVisible"))
    }

    @Test
    fun `generated store should replace declarations from the same type`() {
        val baseline = store(
            "baseline.json",
            listOf(
                adapter("oldAttribute", "android.view.View", "boolean", "demo.MutableAdapters"),
                adapter("untouched", "android.view.View", "int", "demo.UntouchedAdapters"),
            ).joinToString(),
        )
        val generated = store(
            "current.json",
            adapter("newAttribute", "android.view.View", "boolean", "demo.MutableAdapters"),
        )
        val cache = DataBindingSetterStoreCache(temporaryFolder.newFolder("cache"))

        val merged = cache.merge(baseline, generated)

        assertFalse(hasAdapter(merged, "oldAttribute"))
        assertEquals(listOf("demo.MutableAdapters"), adapterTypes(merged, "newAttribute"))
        assertEquals(listOf("demo.UntouchedAdapters"), adapterTypes(merged, "untouched"))
    }

    @Test
    fun `subsequent generated stores should preserve previous incremental entries`() {
        val baseline = store(
            "baseline.json",
            adapter("baseline", "android.view.View", "boolean", "demo.BaselineAdapters"),
        )
        val first = store(
            "first.json",
            adapter("first", "android.view.View", "boolean", "demo.FirstAdapters"),
        )
        val second = store(
            "second.json",
            adapter("second", "android.view.View", "boolean", "demo.SecondAdapters"),
        )
        val cache = DataBindingSetterStoreCache(temporaryFolder.newFolder("cache"))

        cache.merge(baseline, first)
        val merged = cache.merge(baseline, second)

        assertEquals(listOf("demo.BaselineAdapters"), adapterTypes(merged, "baseline"))
        assertEquals(listOf("demo.FirstAdapters"), adapterTypes(merged, "first"))
        assertEquals(listOf("demo.SecondAdapters"), adapterTypes(merged, "second"))
    }

    @Test
    fun `conflicting fragment should not replace last valid merged store`() {
        val baseline = store(
            "baseline.json",
            adapter("shared", "android.view.View", "boolean", "demo.ExistingAdapters"),
        )
        val valid = store(
            "valid.json",
            adapter("unique", "android.view.View", "boolean", "demo.ValidAdapters"),
        )
        val conflict = store(
            "conflict.json",
            adapter("shared", "android.view.View", "boolean", "demo.ConflictingAdapters"),
        )
        val cache = DataBindingSetterStoreCache(temporaryFolder.newFolder("cache"))
        val lastValid = cache.merge(baseline, valid).readText()

        assertFailsWith<IllegalStateException> {
            cache.merge(baseline, conflict)
        }

        assertEquals(lastValid, cache.getMergedStore(baseline)?.readText())
    }

    private fun store(name: String, adapters: String): File {
        return File(temporaryFolder.root, name).apply {
            writeText(
                """
                {
                  "version": 5,
                  "adapterMethods": {$adapters},
                  "renamedMethods": {},
                  "conversionMethods": {},
                  "untaggableTypes": {},
                  "multiValueAdapters": {},
                  "inverseAdapters": {},
                  "inverseMethods": {},
                  "twoWayMethods": {},
                  "useAndroidX": true
                }
                """.trimIndent(),
            )
        }
    }

    private fun adapter(attribute: String, viewType: String, valueType: String, type: String): String {
        return """"$attribute":[[{"viewType":"$viewType","valueType":"$valueType"},{"type":"$type","method":"set","requiresOldValue":false,"isStatic":true,"componentClass":null}]]"""
    }

    private fun hasAdapter(store: File, attribute: String): Boolean {
        return JsonParser.parseString(store.readText())
            .asJsonObject["adapterMethods"]
            .asJsonObject
            .has(attribute)
    }

    private fun adapterTypes(store: File, attribute: String): List<String> {
        val root = JsonParser.parseString(store.readText()).asJsonObject
        val entries = root["adapterMethods"].asJsonObject[attribute].asJsonArray
        return entries.map { entry -> entry.asJsonArray[1].asJsonObject["type"].asString }
    }
}
