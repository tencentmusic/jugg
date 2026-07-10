package com.sickworm.intellij.jugg.gradle.script

import org.junit.Test
import java.io.File

/**
 * Verifies the generated init script against Gradle 6.8 and its Kotlin 1.4 compiler.
 */
class ReadProjectInfoGradle6CompatTest : ReadProjectInfoGradleCompatTestBase() {

    override val gradleVersion = "6.8"

    override val javaHomeForChildProcess: String
        get() = findCompatibleJavaHome()
            ?: error("No Java 8-15 JDK found. Set GRADLE6_JAVA_HOME or JAVA_HOME_11_X64.")

    @Test
    fun generatedScript_shouldRunOnGradle68AndCollectProjectInfo() {
        assertInitScriptRunsAndCollectsProjectInfo()
    }

    override fun buildProjectFiles(projectDir: File) {
        writeFile(
            File(projectDir, "settings.gradle"),
            """
            rootProject.name = 'gradle6-fixture'
            include ':app', ':lib'
            """.trimIndent(),
        )
        writeFile(
            File(projectDir, "build.gradle"),
            """
            allprojects {
                repositories {
                    mavenCentral()
                }
            }
            subprojects {
                apply plugin: 'java-library'
                java {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                    targetCompatibility = JavaVersion.VERSION_1_8
                }
            }
            """.trimIndent(),
        )
        writeFile(File(projectDir, "app/build.gradle"), "dependencies { implementation project(':lib') }")
        writeFile(File(projectDir, "lib/build.gradle"), "")
    }

    private fun findCompatibleJavaHome(): String? {
        val currentJavaHome = System.getProperty("java.home")
        if (Runtime.version().feature() in SUPPORTED_JAVA_FEATURES) {
            return currentJavaHome
        }

        listOf(
            "GRADLE6_JAVA_HOME",
            "JAVA_HOME_11_X64",
            "JAVA_HOME_11_ARM64",
            "JAVA_HOME_8_X64",
            "JAVA_HOME_8_ARM64",
        )
            .firstNotNullOfOrNull { System.getenv(it) }
            ?.let { return it }

        return listOf(
            File("/Library/Java/JavaVirtualMachines"),
            File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
        ).asSequence()
            .flatMap { root -> root.listFiles()?.asSequence() ?: emptySequence() }
            .map { File(it, "Contents/Home") }
            .firstOrNull { javaHome -> readJavaFeature(javaHome) in SUPPORTED_JAVA_FEATURES }
            ?.path
    }

    private fun readJavaFeature(javaHome: File): Int? {
        val version = File(javaHome, "release")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
            ?.substringAfter('=')
            ?.trim('"')
            ?: return null
        return if (version.startsWith("1.")) {
            version.substringAfter("1.").substringBefore('.').toIntOrNull()
        } else {
            version.substringBefore('.').toIntOrNull()
        }
    }

    companion object {
        private val SUPPORTED_JAVA_FEATURES = 8..15
    }
}
