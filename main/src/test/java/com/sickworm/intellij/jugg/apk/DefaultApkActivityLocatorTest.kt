package com.sickworm.intellij.jugg.apk

import com.sickworm.intellij.jugg.apk.manifest.ManifestActivityInfo
import com.sickworm.intellij.jugg.apk.manifest.XmlNode
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultApkActivityLocatorTest {

    private val locator = DefaultApkActivityLocator(logger)

    @Test
    fun `launch activity keeps preferring the launcher with DEFAULT category`() {
        val manifest = manifest(
            activity(".FirstLauncher", intentFilters = listOf(mainIntent("android.intent.category.LAUNCHER"))),
            activity(".DefaultLauncher", intentFilters = listOf(mainIntent("android.intent.category.LAUNCHER", "android.intent.category.DEFAULT"))),
        )

        assertEquals("com.example.DefaultLauncher", locator.computeDefaultActivityFromApks(manifest))
    }

    @Test
    fun `home activity selects the first enabled and exported MAIN HOME activity`() {
        val manifest = manifest(
            activity(".HomeDisabled", mapOf("enabled" to "false"), listOf(mainIntent("android.intent.category.HOME"))),
            activity(".HomeUnexported", mapOf("exported" to "false"), listOf(mainIntent("android.intent.category.HOME"))),
            activity(".HomeActivity", intentFilters = listOf(mainIntent("android.intent.category.HOME"))),
            activity(".SecondHomeActivity", intentFilters = listOf(mainIntent("android.intent.category.HOME"))),
        )

        assertEquals("com.example.HomeActivity", locator.computeHomeActivity(manifest))
    }

    @Test
    fun `home activity is null when no activity declares MAIN with HOME`() {
        val manifest = manifest(
            activity(".MainActivity", intentFilters = listOf(mainIntent("android.intent.category.LAUNCHER"))),
            activity(".PlainActivity", mapOf("exported" to "true")),
        )

        assertNull(locator.computeHomeActivity(manifest))
    }

    @Test
    fun `home activity is null when every MAIN HOME activity is unusable`() {
        val manifest = manifest(
            activity(".DisabledActivity", mapOf("enabled" to "false"), listOf(mainIntent("android.intent.category.HOME"))),
            activity(".UnexportedActivity", mapOf("exported" to "false"), listOf(mainIntent("android.intent.category.HOME"))),
            activity("", intentFilters = listOf(mainIntent("android.intent.category.HOME"))),
        )

        assertNull(locator.computeHomeActivity(manifest))
    }

    @Test
    fun `home activity uses the activity alias qualified name`() {
        val manifest = manifest(
            alias(".AliasActivity", ".RealActivity", listOf(mainIntent("android.intent.category.HOME"))),
        )

        assertEquals("com.example.AliasActivity", locator.computeHomeActivity(manifest))
    }

    private fun manifest(vararg activities: XmlNode): ManifestActivityInfo {
        val application = XmlNode("application", activities.toList(), emptyMap())
        val manifest = XmlNode("manifest", listOf(application), mapOf("package" to "com.example"))
        return ManifestActivityInfo().also { it.parseNode(manifest) }
    }

    private fun activity(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        intentFilters: List<XmlNode> = emptyList(),
    ): XmlNode {
        return XmlNode("activity", intentFilters, mapOf("name" to name) + attributes)
    }

    private fun alias(name: String, targetActivity: String, intentFilters: List<XmlNode>): XmlNode {
        return XmlNode(
            "activity-alias",
            intentFilters,
            mapOf("name" to name, "targetActivity" to targetActivity),
        )
    }

    private fun mainIntent(vararg categories: String): XmlNode {
        val children = listOf(XmlNode("action", emptyList(), mapOf("name" to "android.intent.action.MAIN"))) +
                categories.map { XmlNode("category", emptyList(), mapOf("name" to it)) }
        return XmlNode("intent-filter", children, emptyMap())
    }
}
