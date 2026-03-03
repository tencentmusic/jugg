package com.sickworm.intellij.jugg.ide.logic

import java.io.File
import java.io.FileNotFoundException

/**
 * Exports client setup guide into Jugg config directory for quick local viewing.
 */
object ClientSetupDocExporter {

    private const val RESOURCE_PATH = "docs/skills/install/client_setup.md"

    fun export(projectDir: File): File {
        val target = File(projectDir, "build/jugg/config/client_setup.md")
        target.parentFile?.mkdirs()
        val stream = ClientSetupDocExporter::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: throw FileNotFoundException("Resource not found: $RESOURCE_PATH")
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            target.writeText(reader.readText(), Charsets.UTF_8)
        }
        return target
    }
}
