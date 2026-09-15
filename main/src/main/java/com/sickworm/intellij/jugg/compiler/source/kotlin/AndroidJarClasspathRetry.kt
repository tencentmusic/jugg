package com.sickworm.intellij.jugg.compiler.source.kotlin

import java.io.File

/**
 * AndroidJarClasspathRetry recovers compilation when the public SDK android.jar sits before a more
 * complete framework/HideAPI jar on the classpath, which is a normal setup for ROM and car system
 * apps. Kotlin resolves a nested classifier through its outer class, so the stripped public stub
 * wins and its @hide nested supertype can not be found even though a later jar provides it.
 * Moving android.jar to the end lets the later jar take over, like a cheap classpath overlay.
 */
object AndroidJarClasspathRetry {

    private const val ANDROID_JAR_NAME = "android.jar"

    // Renderings of "this supertype can not be accessed" across compiler versions. Kotlin 2.2+
    // prints only the simple name, so the package can not be used to narrow the match.
    private val SUPERTYPE_DIAGNOSTICS = listOf(
        "which is a supertype of",
        "unresolved supertypes:",
    )

    /** Returns true when any message reports a supertype the compiler could not access. */
    fun isShadowedSupertypeDiagnostic(errorMessages: Iterable<String>): Boolean {
        return errorMessages.any { message ->
            SUPERTYPE_DIAGNOSTICS.any { message.contains(it, ignoreCase = true) }
        }
    }

    /**
     * Moves the first SDK android.jar to the end, keeping every other entry in relative order.
     * Returns the original list when there is no android.jar or it is already last.
     */
    fun postponeSdkAndroidJar(classpath: List<String>): List<String> {
        val index = classpath.indexOfFirst { File(it).name == ANDROID_JAR_NAME }
        if (index < 0 || index == classpath.lastIndex) {
            return classpath
        }
        return classpath.filterIndexed { i, _ -> i != index } + classpath[index]
    }
}
