package com.sickworm.intellij.jugg.loader

import com.google.gson.Gson
import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals

class JuggHotUpdateBootstrapTest {

    @Test
    fun `load manifest is active only for matching embedded build`() {
        val directory = Files.createTempDirectory("jugg-hot-update-manifest").toFile()
        val manifestFile = directory.resolve("load_manifest.json")
        val manifest = HotUpdateLoadManifest("embedded-2", listOf("main.jar", "idea.jar"))
        manifestFile.writeText(Gson().toJson(manifest))

        assertContentEquals(
            manifest.jarFileNames.toTypedArray(),
            JuggHotUpdateBootstrap.resolveActiveJarFileNames(manifestFile, "embedded-2"),
        )
        assertContentEquals(emptyArray(), JuggHotUpdateBootstrap.resolveActiveJarFileNames(manifestFile, "embedded-1"))
    }

    @Test
    fun `legacy load list is ignored`() {
        val directory = Files.createTempDirectory("jugg-hot-update-legacy").toFile()
        directory.resolve("load_list.txt").writeText("main.jar")

        assertContentEquals(
            emptyArray(),
            JuggHotUpdateBootstrap.resolveActiveJarFileNames(directory.resolve("load_manifest.json"), "embedded-1"),
        )
    }
}
