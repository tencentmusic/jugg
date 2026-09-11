package com.sickworm.intellij.jugg.apk

import com.android.tools.deployer.model.Apk
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
        val apkEntry = Mockito.mock(com.android.tools.deployer.model.ApkEntry::class.java)
        val constructor = Apk::class.java.declaredConstructors.maxByOrNull { it.parameterCount }!!
        constructor.isAccessible = true
        var stringIndex = 0
        val args = constructor.parameterTypes.map { type ->
            when {
                type == String::class.java -> listOf(path, "checksum", path, "com.example.app")
                    .getOrElse(stringIndex++) { "" }
                java.util.List::class.java.isAssignableFrom(type) -> emptyList<Any>()
                java.util.Map::class.java.isAssignableFrom(type) -> entries.associateWith { apkEntry }
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        return constructor.newInstance(*args.toTypedArray()) as Apk
    }
}
