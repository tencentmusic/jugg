package com.sickworm.intellij.jugg.gradle.script

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Verifies that readProjectInfo.gradle.kts compiles and runs correctly on Gradle 7.3.3,
 * which uses Kotlin 1.5. This catches regressions like:
 * - `private` modifier on top-level functions (not allowed in Kotlin 1.5)
 * - `Char.code` property (Kotlin 1.5+ API)
 * - extension property references in constructor default arguments (Kotlin 1.5 backend crash)
 * - Reflector companion constructing inner class from static context (getOuterExpression crash)
 */
class ReadProjectInfoGradle7CompatTest : ReadProjectInfoGradleCompatTestBase() {

    override val gradleVersion = "7.3.3"

    @Test
    fun generatedScript_shouldRunOnGradle733AndCollectFileDependencies() {
        assertInitScriptRunsAndCollectsProjectInfo()
    }

    /**
     * Verifies the script runs on an Android application project with
     * `-Pjugg.inject.application.enable=true`, which triggers the GradleApplicationInjector
     * code path that uses Reflector. Previously caused NoSuchMethodError on Gradle 7 (Kotlin 1.5)
     * when the Reflector companion was flattened to top-level functions.
     */
    @Test
    fun generatedScript_shouldRunOnAndroidAppWithInjectApplicationEnabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            extraArgs = listOf("-P${PARAM_INJECT_ENABLE}=true"),
        )
        assertEquals(0, result.exitCode, "Gradle $gradleVersion android fixture failed.\n${result.output}")
    }

    /**
     * Verifies that processDebugManifest succeeds with `-Pjugg.inject.application.enable=true`
     * on Gradle 7 (Kotlin 1.5). This exercises the doLast execution phase, which triggers
     * InitScriptManifestXmlHelper instantiation — a sibling inner class construction that
     * previously caused NoSuchMethodError in Kotlin 1.5 .kts scripts.
     */
    @Test
    fun generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            task = ":app:processDebugManifest",
            extraArgs = listOf("-P${PARAM_INJECT_ENABLE}=true"),
        )
        assertEquals(0, result.exitCode, "Gradle $gradleVersion manifest task failed.\n${result.output}")
    }

    override fun buildProjectFiles(projectDir: File) {
        writeFile(
            File(projectDir, "settings.gradle"),
            """
            rootProject.name = 'gradle7-fixture'
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
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
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

/** Mirrors GradleApplicationInjector.PARAM_ENABLE without pulling in the production class. */
private const val PARAM_INJECT_ENABLE = "jugg.inject.application.enable"
