package com.sickworm.intellij.jugg.apk.manifest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestActivityInfoTest {

    @Test
    fun `reads application use32bitAbi`() {
        assertTrue(parseManifest(mapOf("use32bitAbi" to "true")).use32bitAbi())
        assertFalse(parseManifest(emptyMap()).use32bitAbi())
    }

    private fun parseManifest(applicationAttributes: Map<String, String>): ManifestActivityInfo {
        val application = XmlNode("application", emptyList(), applicationAttributes)
        val manifest = XmlNode("manifest", listOf(application), mapOf("package" to "com.example.app"))
        return ManifestActivityInfo().also { it.parseNode(manifest) }
    }
}
