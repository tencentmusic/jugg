package com.sickworm.intellij.jugg.apk


/**
 * BuildToolsVersionComparator wrapper for Android build-tools version folder names.
 * Collaboration: Used by [ApkFileModifier] to pick the latest usable build-tools directory.
 * Data Contract: [compareTo] compares numeric tokens split by `.`, `_rc`, and `-rc`, with non-numeric tokens treated as `-1`.
 */
class BuildToolsVersionComparator(
    private val versionString: String,
): Comparable<BuildToolsVersionComparator> {

    override fun compareTo(other: BuildToolsVersionComparator): Int {
        val splitRegex = Regex("\\.|_rc|-rc")
        val my = this
        val myVersion = my.versionString.substringAfter("android-")
        val myVersions = myVersion.split(splitRegex)

        val otherVersion = other.versionString.substringAfter("android-")
        val otherVersions = otherVersion.split(splitRegex)

        myVersions.forEachIndexed { index, subVersion ->
            val myVersionInt = subVersion.toIntOrNull() ?: -1
            val otherVersionInt = otherVersions.getOrNull(index)?.toIntOrNull() ?: -1
            if (myVersionInt != otherVersionInt) {
                return myVersionInt - otherVersionInt
            }
        }

        if (myVersions.size != otherVersions.size) {
            return myVersions.size - otherVersions.size
        }
        return 0
    }
}
