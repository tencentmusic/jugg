package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * KotlinCompilerHostCompat works around host JDK incompatibilities when old Kotlin compilers
 * (< 2.1.20) run inside the IDE process on JDK 25+.
 *
 * Those compilers shade an intellij-core `JavaVersion` whose `parse()` caps the accepted java
 * feature version below 25 (fixed upstream in Kotlin 2.1.20, commit e0bf708). On a JDK 25+ host
 * the first `JavaVersion.current()` call inside the compiler throws IllegalArgumentException
 * (e.g. "25.0.3") and the whole compilation fails with INTERNAL_ERROR.
 */
object KotlinCompilerHostCompat {

    private const val SHADED_JAVA_VERSION_CLASS = "org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion"

    private const val MIN_BROKEN_HOST_JAVA_FEATURE = 25

    /**
     * Probes the shaded JavaVersion loaded by [compilerClassLoader]. When it can not parse the
     * host java version, presets its `current` cache with the real host feature version via
     * `compose()`, which bypasses the broken string parsing. No-op for compilers (>= 2.1.20)
     * that can parse the host version by themselves, so validated environments are unaffected.
     */
    fun ensureShadedJavaVersionSupported(compilerClassLoader: ClassLoader, logger: Logger) {
        val hostFeature = Runtime.version().feature()
        try {
            val clazz = compilerClassLoader.loadClass(SHADED_JAVA_VERSION_CLASS)
            try {
                clazz.getMethod("current").invoke(null)
                return
            } catch (e: InvocationTargetException) {
                logger.debug("shaded JavaVersion can not parse host java version: ${e.cause?.message}")
            }
            val composed = clazz.getMethod("compose", Int::class.javaPrimitiveType)
                .invoke(null, hostFeature)
            clazz.getDeclaredField("current").apply {
                isAccessible = true
                set(null, composed)
            }
            logger.debug("preset shaded JavaVersion.current to $hostFeature for host JDK compatibility")
        } catch (e: Exception) {
            logger.debug("preset shaded JavaVersion failed, skip", e)
        }
    }

    /**
     * Returns true when the compile command should carry `-no-jdk`, which stops the compiler from
     * mounting the host JDK as platform classpath and resolves `java.*` from android.jar instead,
     * aligning with AGP/KGP gradle compilation. Restricted to JDK 25+ hosts (where the current
     * behavior is broken anyway) to keep validated environments unchanged.
     */
    fun shouldUseNoJdk(hostJavaFeature: Int, dependencies: List<String>): Boolean {
        if (hostJavaFeature < MIN_BROKEN_HOST_JAVA_FEATURE) {
            return false
        }
        return dependencies.any { File(it).name == "android.jar" }
    }
}
