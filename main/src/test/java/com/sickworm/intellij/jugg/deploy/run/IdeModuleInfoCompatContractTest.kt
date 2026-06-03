package com.sickworm.intellij.jugg.deploy.run

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdeModuleInfoCompatContractTest {

    @Test
    fun `all compat IdeModuleInfo builders provide androidTest metadata fields`() {
        val projectRoot = findProjectRoot()
        val compatFiles = File(projectRoot, "deploy_compat")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.path.contains("/src/main/java/") }
            .filter { it.readText().contains("override fun getIdeModuleInfo") }
            .toList()

        val missingFiles = compatFiles.filter { file ->
            val text = file.readText()
            text.contains("IdeModuleInfo(") &&
                (!text.contains("androidTestApplicationId =") ||
                    !text.contains("androidTestInstrumentationTargetPackage ="))
        }.map { it.relativeTo(projectRoot).path }

        assertTrue(
            "Missing androidTest metadata fields in compat IdeModuleInfo builders: $missingFiles",
            missingFiles.isEmpty(),
        )
    }

    private fun findProjectRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (current.parentFile != null) {
            if (File(current, "deploy_compat").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        error("Cannot find project root from ${System.getProperty("user.dir")}")
    }
}
