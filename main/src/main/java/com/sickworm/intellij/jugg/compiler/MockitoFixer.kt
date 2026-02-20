package com.sickworm.intellij.jugg.compiler

import java.io.File

/**
 * MockitoFixer applies environment workarounds for known Mockito/ByteBuddy startup issues.
 */
object MockitoFixer {

    fun tryFix() {
        if (isWindows) {
            return
        }

        // actually is fixing ByteBuddyAgent used by Mockito

        // 1. ByteBuddyAgent will read System.setProperty("java.home") and invoke,
        // when the property has white space，it will add " between the path,
        // which will cause invoke failed

        // 2. JDK 1.8 will cause invoke failed"Could not self-attach to current VM using external process",
        // need to use JDK 11
        // now project required JDK 17, so this is not a problem anymore.

        println("\ntryFix Mockito crash start")

        var propertyJavaHome = System.getProperty("java.home")
        val envJavaHome = System.getenv("JAVA_HOME")
        println("propertyJavaHome: $propertyJavaHome, envJavaHome: $envJavaHome")

//        if (propertyJavaHome.contains(" ")) {
//            // manual fix by replace with envJavaHome
//            if (envJavaHome == null || envJavaHome.contains(" ")) {
//                throw IllegalStateException("please specific \$JAVA_HOME without white space, or Mockito won't work.")
//            }
//            System.setProperty("java.home", envJavaHome)
//            propertyJavaHome = envJavaHome
//        }

        println("tryFix Mockito crash end\n")
    }

    private fun getJdkVersion(javaHome: String): String {
        val process = Runtime.getRuntime().exec("bin/java -version", null, File(javaHome))
        val output = String(process.errorStream.readBytes())
        process.waitFor()
        return output
    }
}
