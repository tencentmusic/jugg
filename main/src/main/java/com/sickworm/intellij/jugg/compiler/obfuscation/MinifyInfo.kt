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
