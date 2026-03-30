package com.sickworm.intellij.jugg.compiler.obfuscation

import java.io.File

/**
 * Inline impact information required for obfuscation processing
 *
 * Phase 1: Detection and logging ✅
 * - Detect which classes are affected by R8 inline optimization
 * - Log inline impacts for performance analysis
 *
 * Phase 2: Complete redirection handling (current implementation)
 * - Generate _jugg_fix classes
 * - Redirect calls in DEX
 * - Avoid cascading recompilation caused by inlining
 */
data class MinifyInfo(
    /**
     * List of classes affected by inlining
     * Methods of these classes are inlined into other classes by R8,
     * modifying these methods will affect callers
     */
    val inlineEffectedClasses: List<InlineEffectedClass>,

    /**
     * Mapping from class name to .class file path
     * Used to generate DEX files for _jugg_fix classes
     *
     * Phase 2 new field
     */
    val classFiles: Map<String, File> = emptyMap()
) {
    /**
     * Filtered list of inlineEffectedClasses that only includes classes with
     * corresponding .class files in [classFiles].
     *
     * This prevents redirect mapping for classes whose _jugg_fix DEX cannot be
     * generated (e.g., boot classpath classes like java/lang/Object that are
     * never shipped in the APK and have no .class file in project classpath).
     */
    val effectiveInlineEffectedClasses: List<InlineEffectedClass> by lazy {
        if (classFiles.isEmpty()) {
            emptyList()
        } else {
            inlineEffectedClasses.filter { effectedClass ->
                // Convert className from sig format (Lcom/example/MyClass;) to dot format (com.example.MyClass)
                val dotName = if (effectedClass.className.startsWith("L") && effectedClass.className.endsWith(";")) {
                    effectedClass.className.substring(1, effectedClass.className.length - 1).replace('/', '.')
                } else {
                    effectedClass.className.replace('/', '.')
                }
                classFiles.containsKey(dotName)
            }
        }
    }

    companion object {
        val EMPTY = MinifyInfo(
            inlineEffectedClasses = emptyList(),
            classFiles = emptyMap()
        )
    }
}

/**
 * Information about classes affected by inlining
 */
data class InlineEffectedClass(
    val className: String,
    val effectedByClasses: List<String>
)
