package com.sickworm.intellij.jugg.gradle.script

/**
 * VersionResolver compares semantic-like version strings for update decisions.
 */
object VersionResolver {

    fun isNewerVersion(version: String, oldVersion: String?): Boolean {
        if (oldVersion == null) {
            return true
        }

        val versionRegex = "(\\d+|\\D+)".toRegex()

        val version1Parts = versionRegex.findAll(version).map { it.value }.toList()
        val version2Parts = versionRegex.findAll(oldVersion).map { it.value }.toList()

        val maxSize = maxOf(version1Parts.size, version2Parts.size)
        val version1Extended = version1Parts + List(maxSize - version1Parts.size) { "" }
        val version2Extended = version2Parts + List(maxSize - version2Parts.size) { "" }

        for (i in 0 until maxSize) {
            val part1 = version1Extended[i]
            val part2 = version2Extended[i]

            val comparisonResult = comparePart(part1, part2)
            if (comparisonResult != 0) {
                return comparisonResult > 0
            }
        }

        return false // If both are equal, return version1
    }

    private fun comparePart(part1: String, part2: String): Int {
        return try {
            val num1 = part1.toInt()
            val num2 = part2.toInt()
            num1.compareTo(num2)
        } catch (e: NumberFormatException) {
            part1.compareTo(part2)
        }
    }
}
