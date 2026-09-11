package com.sickworm.intellij.jugg.compiler.databinding

import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import java.io.File

/**
 * Rewrites AGP 7+ ViewBinding lookup calls so they compile against viewbinding runtimes
 * that do not contain [androidx.viewbinding.ViewBindings], such as AGP 4.2.2.
 */
object LegacyViewBindingLookup {
    const val VIEW_BINDINGS_DESCRIPTOR = "Landroidx/viewbinding/ViewBindings;"

    private val findChildCallRegex = Regex(
        """(?:androidx\.viewbinding\.)?ViewBindings\.findChildViewById\(([^,]+),\s*""",
    )
    private val viewBindingsImportRegex = Regex("""import\s+androidx\.viewbinding\.ViewBindings;\s*\n""")

    fun rewriteJavaSource(source: String): String {
        if (!source.contains("ViewBindings.findChildViewById")) {
            return source
        }
        return source
            .replace(findChildCallRegex, "$1.findViewById(")
            .replace(viewBindingsImportRegex, "")
    }

    fun rewriteGeneratedJava(outputDir: File) {
        if (!outputDir.exists()) {
            return
        }
        outputDir.listFilesRecursively()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val original = file.readText()
                val rewritten = rewriteJavaSource(original)
                if (rewritten != original) {
                    file.writeText(rewritten)
                }
            }
    }
}
