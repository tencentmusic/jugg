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
        // Remove SNAPSHOT suffix for comparison
        val cleanA = a.replace("-SNAPSHOT", "")
        val cleanB = b.replace("-SNAPSHOT", "")
        
        // Separate main version number and suffix (rc, feature, etc.)
        val (versionA, suffixA) = parseVersionAndSuffix(cleanA)
        val (versionB, suffixB) = parseVersionAndSuffix(cleanB)
        
        // First compare main version numbers
        val versionResult = compareVersionNumbers(versionA, versionB)
        if (versionResult != 0) {
            return versionResult
        }
        
        // When main version numbers are the same, compare suffixes
        return compareSuffix(suffixA, suffixB)
    }
    
    private fun parseVersionAndSuffix(version: String): Pair<String, String> {
        val parts = version.split("-", limit = 2)
        return if (parts.size == 2) {
            Pair(parts[0], parts[1])
        } else {
            Pair(parts[0], "")
        }
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
    
    private fun compareSuffix(a: String, b: String): Int {
        // Empty suffix (release version) > rc version > feature version
        val priorityA = getSuffixPriority(a)
        val priorityB = getSuffixPriority(b)
        
        if (priorityA != priorityB) {
            return priorityA.compareTo(priorityB)
        }
        
        // Same type suffix, compare specific content
        return when {
            a.startsWith("rc") && b.startsWith("rc") -> {
                val rcNumA = a.substring(2).toIntOrNull() ?: 0
                val rcNumB = b.substring(2).toIntOrNull() ?: 0
                rcNumA.compareTo(rcNumB)
            }
            else -> a.compareTo(b)
        }
    }
    
    private fun getSuffixPriority(suffix: String): Int {
        return when {
            suffix.isEmpty() -> 3 // Release version has highest priority
            suffix.startsWith("rc") -> 2 // RC version comes second
            suffix.startsWith("feature") -> 1 // Feature version has lowest priority
            else -> 0 // Other unknown suffixes have lowest priority
        }
    }
}