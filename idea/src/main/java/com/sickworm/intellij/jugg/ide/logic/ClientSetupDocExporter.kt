package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Exports client setup guide into Jugg config directory for quick local viewing.
 */
object ClientSetupDocExporter {

    private const val RESOURCE_PATH = "docs/skills/install/client_setup.md"
    private const val SKILL_NAME = "jugg-android-dev-loop"
    private const val SKILL_ZIP_RESOURCE = "docs/skills/jugg-android-dev-loop.zip"
    private const val SKILL_SOURCE_LITERAL = "docs/skills/$SKILL_NAME"
    private const val SKILL_SOURCE_PLACEHOLDER = "docs/skills/\${SKILL_NAME}"
    private const val SKILL_SOURCE_RUNTIME = "./$SKILL_NAME"

    /**
     * Exports setup guide and bundled skill to the same runtime directory.
     */
    fun export(projectDir: File): File {
        val targetDir = JuggPathManager(projectDir).configDir
        val target = File(targetDir, "client_setup.md")
        targetDir.mkdirs()
        exportSkill(targetDir)
        val stream = ClientSetupDocExporter::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: throw FileNotFoundException("Resource not found: $RESOURCE_PATH")
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val markdown = reader.readText()
                .replace(SKILL_SOURCE_LITERAL, SKILL_SOURCE_RUNTIME)
                .replace(SKILL_SOURCE_PLACEHOLDER, SKILL_SOURCE_RUNTIME)
            target.writeText(markdown, Charsets.UTF_8)
        }
        return target
    }

    private fun exportSkill(targetDir: File) {
        val skillDir = File(targetDir, SKILL_NAME)
        if (skillDir.exists()) {
            skillDir.deleteRecursively()
        }
        skillDir.mkdirs()

        val stream = ClientSetupDocExporter::class.java.classLoader.getResourceAsStream(SKILL_ZIP_RESOURCE)
            ?: throw FileNotFoundException("Resource not found: $SKILL_ZIP_RESOURCE")

        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(skillDir, entry.name)
                val canonicalParent = skillDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(canonicalParent)) {
                    throw IOException("Invalid zip entry path")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
