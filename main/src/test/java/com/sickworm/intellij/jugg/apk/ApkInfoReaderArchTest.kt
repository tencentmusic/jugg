package com.sickworm.intellij.jugg.apk

import com.sickworm.intellij.jugg.deploy.api.Apk
import com.sickworm.intellij.jugg.deploy.api.ApkEntry
import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class ApkInfoReaderArchTest {

    private val reader = ApkInfoReader(Mockito.mock(Logger::class.java))

    @Test
    fun `APK without native libraries keeps ABI unknown`() {
        assertEquals("ARCH_UNKNOWN", reader.getArch(listOf(apk("base.apk"))))
    }

    @Test
    fun `resource split does not erase 32 bit ABI evidence`() {
        assertEquals(
            "ARCH_32_BIT",
            reader.getArch(listOf(
                apk("base.apk", "lib/armeabi-v7a/libapp.so"),
                apk("split_resources.apk", "res/layout/main.xml"),
            )),
        )
    }

    @Test
    fun `APK containing both ARM bitnesses keeps ABI unknown`() {
        assertEquals(
            "ARCH_UNKNOWN",
            reader.getArch(listOf(apk(
                "base.apk",
                "lib/armeabi-v7a/libapp.so",
                "lib/arm64-v8a/libapp.so",
            ))),
        )
    }

    private fun apk(path: String, vararg entries: String): Apk {
        val placeholder = Apk(path, "", path, "com.example.app", emptyList(), emptyList(), emptyList(), emptyMap())
        return Apk(
            path,
            "checksum",
            path,
            "com.example.app",
            emptyList(),
            emptyList(),
            emptyList(),
            entries.associateWith { ApkEntry(it, 0L, placeholder) },
        )
    }
}
