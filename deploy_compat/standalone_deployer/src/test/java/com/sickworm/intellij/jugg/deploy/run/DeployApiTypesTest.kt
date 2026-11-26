package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class DeployApiTypesTest {

    @Test
    fun `apk defensively copies collections and binds entries to itself`() {
        val libraryAbis = mutableListOf("arm64-v8a")
        val targetPackages = mutableListOf("target")
        val sdkLibraries = mutableListOf("sdk")
        val placeholder = Apk("placeholder.apk", "", "", "", emptyList(), emptyList(), emptyList(), emptyMap())
        val entries = mutableMapOf("classes.dex" to ApkEntry("classes.dex", 1L, placeholder))

        val apk = Apk("base.apk", "checksum", "/base.apk", "demo", libraryAbis, targetPackages, sdkLibraries, entries)
        libraryAbis += "x86"
        targetPackages.clear()
        sdkLibraries += "other"
        entries.clear()

        assertEquals(listOf("arm64-v8a"), apk.libraryAbis)
        assertEquals(listOf("target"), apk.targetPackages)
        assertEquals(listOf("sdk"), apk.sdkLibraries)
        assertEquals(setOf("classes.dex"), apk.apkEntries.keys)
        assertSame(apk, apk.apkEntries.getValue("classes.dex").apk)
        assertEquals("base.apk/classes.dex", apk.apkEntries.getValue("classes.dex").qualifiedPath)
        assertFailsWith<UnsupportedOperationException> { (apk.libraryAbis as MutableList).add("x86") }
        assertFailsWith<UnsupportedOperationException> { (apk.apkEntries as MutableMap).clear() }
    }

    @Test
    fun `apk does not expose entry replacement method`() {
        assertFalse(Apk::class.java.declaredMethods.any { it.name.contains("replaceEntries") })
    }
}
