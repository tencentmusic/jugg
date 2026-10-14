package com.sickworm.intellij.jugg.ide.logic

/**
 * Plugin version comparator
 * Supports comparison of complex version number formats, including:
 * - Basic versions: 3.1.0, 3.2.0
 * - RC versions: 3.1.0-rc1, 3.1.0-rc2
 * - Feature versions: 3.1.0-feature-abc
 * - SNAPSHOT versions: 3.1.0-SNAPSHOT, 3.1.0-rc1-SNAPSHOT
 * - Complex versions: 4.0.0-rc3-feature-abc-SNAPSHOT
 */
object PluginVersionComparator {

    /**
     * Compare two version numbers
     * @param a Version A
     * @param b Version B
     * @return Negative number means a < b, 0 means a == b, positive number means a > b
     */
    fun compare(a: String, b: String): Int {
        val cleanA = cleanVersion(a)
        val cleanB = cleanVersion(b)
        return compareVersionNumbers(cleanA, cleanB)
    }

    private fun cleanVersion(version: String): String {
        return version
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .trim()
    }

    private fun compareVersionNumbers(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(partsA.size, partsB.size)

        for (i in 0 until maxLength) {
            val partA = if (i < partsA.size) partsA[i] else 0
            val partB = if (i < partsB.size) partsB[i] else 0

            if (partA != partB) {
                return partA.compareTo(partB)
            }
        }

        return 0
    }
}
