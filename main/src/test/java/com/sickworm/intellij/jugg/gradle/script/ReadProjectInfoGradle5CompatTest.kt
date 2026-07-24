package com.sickworm.intellij.jugg.gradle.script

import org.junit.Test
import java.io.File

/**
 * Verifies that readProjectInfo.gradle.kts compiles and runs correctly on Gradle 5.4.1,
 * which is the minimum Gradle version required by AGP 3.5. This version uses Kotlin 1.3.x,
 * catching regressions that are invisible on newer Gradle versions, such as:
 * - Kotlin stdlib APIs introduced after 1.3 (e.g. firstNotNullOfOrNull, Char.code)
 * - Kotlin DSL scripting limitations present in Kotlin 1.3
 *
 * Gradle 5.x requires Java 8. When the current JVM is newer, this test looks for a
 * Java 8 JDK in the following order (no hard-coded paths):
 *   1. GRADLE5_JAVA_HOME  – explicit local/CI override
 *   2. JAVA_HOME_8_X64 / JAVA_HOME_8_ARM64  – GitHub Actions toolchain env
 *   3. /Library/Java/JavaVirtualMachines and ~/Library/Java/JavaVirtualMachines (macOS)
 *      – version verified via the `release` file, independent of directory naming
 * If none is found the test fails with a clear message.
 */
class ReadProjectInfoGradle5CompatTest : ReadProjectInfoGradleCompatTestBase() {

    override val gradleVersion = "5.4.1"

    /**
     * Searches for a Java 8 home. Prints every candidate and its result for diagnostics.
     *
     * Lookup order:
     *   1. Current JVM (if feature version == 8)
     *   2. GRADLE5_JAVA_HOME  – explicit local/CI override
     *   3. JAVA_HOME_8_X64 / JAVA_HOME_8_ARM64  – GitHub Actions toolchain env
     *   4. macOS JVM directories: /Library/Java/JavaVirtualMachines and
     *      ~/Library/Java/JavaVirtualMachines – version verified via the `release` file
     *
     * Returns the first found path, or null if none is available.
     */
    private fun findJava8Home(): String? {
        val currentJavaHome = System.getProperty("java.home")
        val currentFeature = Runtime.version().feature()
        println("[Gradle5CompatTest] Current JVM: Java $currentFeature, JAVA_HOME=$currentJavaHome")

        if (currentFeature == 8) {
            println("[Gradle5CompatTest] Using current JVM (Java 8)")
            return currentJavaHome
        }

        val envCandidates = listOf("GRADLE5_JAVA_HOME", "JAVA_HOME_8_X64", "JAVA_HOME_8_ARM64")
        for (envKey in envCandidates) {
            val value = System.getenv(envKey)
            println("[Gradle5CompatTest] env $envKey=${value ?: "<not set>"}")
            if (value != null) {
                println("[Gradle5CompatTest] Using $envKey=$value")
                return value
            }
        }

        scanMacosJvmDirs().firstOrNull()?.let { return it }

        return null
    }

    /**
     * Scans standard macOS JVM install directories and returns JAVA_HOME paths whose
     * `release` file reports Java 8 (JAVA_VERSION starting with "1.8").
     * Each candidate is printed for diagnostics regardless of outcome.
     */
    private fun scanMacosJvmDirs(): List<String> {
        val jvmRoots = listOf(
            File("/Library/Java/JavaVirtualMachines"),
            File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
        )
        val result = mutableListOf<String>()
        for (root in jvmRoots) {
            val entries = root.listFiles() ?: continue
            for (jdkBundle in entries.sortedBy { it.name }) {
                // macOS JDK bundle layout: <bundle>.jdk/Contents/Home
                val javaHome = File(jdkBundle, "Contents/Home")
                val releaseFile = File(javaHome, "release")
                val version = releaseFile.takeIf { it.exists() }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
                    ?.removePrefix("JAVA_VERSION=")
                    ?.trim('"')
                println("[Gradle5CompatTest] macOS scan ${javaHome.path}: JAVA_VERSION=${version ?: "<unreadable>"}")
                if (version != null && version.startsWith("1.8")) {
                    result += javaHome.path
                }
            }
        }
        return result
    }

    override val javaHomeForChildProcess: String
        get() = findJava8Home() ?: System.getProperty("java.home")

    @Test
    fun generatedScript_shouldRunOnGradle541AndCollectFileDependencies() {
        val java8Home = findJava8Home()
        requireNotNull(java8Home) {
            "No Java 8 JDK found. Gradle 5.4.1 requires Java 8. " +
                "Install a Java 8 JDK (it will be auto-detected from /Library/Java/JavaVirtualMachines or " +
                "~/Library/Java/JavaVirtualMachines on macOS), or set env var GRADLE5_JAVA_HOME, " +
                "JAVA_HOME_8_X64, or JAVA_HOME_8_ARM64."
        }
        assertInitScriptRunsAndCollectsProjectInfo()
    }

    override fun buildProjectFiles(projectDir: File) {
        writeFile(
            File(projectDir, "settings.gradle"),
            """
            rootProject.name = 'gradle5-fixture'
            include ':app', ':lib'
            """.trimIndent(),
        )
        writeFile(
            File(projectDir, "build.gradle"),
            """
            allprojects {
                buildDir = new File(rootProject.projectDir, "build/${'$'}{project.name}")
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
        writeFile(
            File(projectDir, "lib/build.gradle"),
            """
            task demoLibTask {
                doLast {
                    println 'demo-lib'
                }
            }
            """.trimIndent(),
        )
        writeFile(
            File(projectDir, "app/build.gradle"),
            """
            dependencies {
                implementation project(':lib')
                implementation files('libs/local.jar')
            }
            task demoAppTask {
                doLast {
                    println 'demo-app'
                }
            }
            """.trimIndent(),
        )
        createMinimalJar(File(projectDir, "app/libs/local.jar"))
    }
}
