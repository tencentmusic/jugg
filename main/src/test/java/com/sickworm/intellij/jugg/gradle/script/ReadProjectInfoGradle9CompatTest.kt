package com.sickworm.intellij.jugg.gradle.script

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.mockito.Mockito.mock
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadProjectInfoGradle9CompatTest : ReadProjectInfoGradleCompatTestBase() {

    override val gradleVersion = "9.2.1"

    @Test
    fun generatedScript_shouldRunOnGradle921AndCollectFileDependencies() {
        assertInitScriptRunsAndCollectsProjectInfo()

        // Gradle 9 uses Kotlin 1.9+ which fully supports companion flattening:
        // verify that Reflector-based dependency reading also works correctly
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_9_2_1_deps").toFile()
        try {
            buildProjectFiles(fixtureDir)
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)
            val result = runGradle(fixtureDir, "help", "-I", initScript.absolutePath, "--console=plain", "--no-daemon")
            assertEquals(0, result.exitCode, "Gradle $gradleVersion dependency check failed.\n${result.output}")
            val outputFile = JuggPathManager(fixtureDir).gradleProjectInfoFile
            val projectInfo = ProjectInfoSerializer(outputFile, mock(Logger::class.java)).load(isSkipVersionCheck = true)
            assertNotNull(projectInfo)
            val appModule = projectInfo.modules.getValue("app")
            assertTrue(appModule.moduleDependencies.any { it.moduleName == "lib" })
            assertTrue(appModule.libraryDependencies.any { dep ->
                dep.file.name == "local.jar" &&
                    dep.file.absolutePath.endsWith("app${File.separator}libs${File.separator}local.jar")
            })
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    override fun buildProjectFiles(projectDir: File) {
        writeFile(
            File(projectDir, "settings.gradle"),
            """
            rootProject.name = 'gradle9-fixture'
            include ':app', ':lib'
            """.trimIndent(),
        )
        writeFile(
            File(projectDir, "build.gradle"),
            """
            allprojects {
                layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/${'$'}{project.name}"))
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

    /**
     * Verifies the script runs on an Android application project with
     * `-Pjugg.inject.application.enable=true` on Gradle 9 / AGP 8.7.
     */
    @Test
    fun generatedScript_shouldRunOnAndroidAppWithInjectApplicationEnabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp9",
            extraArgs = listOf("-P${PARAM_INJECT_ENABLE}=true"),
        )
        assertEquals(0, result.exitCode, "Gradle $gradleVersion android fixture failed.\n${result.output}")
        assertFalse(
            result.output.contains("Jugg: reflect invoke method failed"),
            "Non-Kotlin Android modules must not trigger Kotlin options reflection errors.\n${result.output}",
        )
        assertFalse(
            result.output.contains("Jugg: can not find kotlin compile task"),
            "Non-Kotlin Android modules must not probe compileKotlin tasks.\n${result.output}",
        )
        assertFalse(
            result.output.contains("resolved during configuration time"),
            "Empty dependency configurations must not be resolved during project info reading.\n${result.output}",
        )
    }

    /**
     * Verifies that processDebugManifest succeeds with `-Pjugg.inject.application.enable=true`
     * on Gradle 9 / AGP 8.7. Exercises the doLast execution phase (InitScriptManifestXmlHelper).
     */
    @Test
    fun generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp9",
            task = ":app:processDebugManifest",
            extraArgs = listOf("-P${PARAM_INJECT_ENABLE}=true"),
        )
        assertEquals(0, result.exitCode, "Gradle $gradleVersion manifest task failed.\n${result.output}")
    }

    /**
     * Verifies that the configuration phase succeeds on AGP 9.0 (which removes applicationVariants)
     * with `-Pjugg.inject.application.enable=true`. The injector must use androidComponents API.
     */
    @Test
    fun generatedScript_shouldRunOnAgp90WithInjectApplicationEnabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp90",
            extraArgs = listOf("-P${PARAM_INJECT_ENABLE}=true"),
        )
        assertEquals(0, result.exitCode, "Gradle $gradleVersion AGP 9.0 config phase failed.\n${result.output}")
    }

    /**
     * Verifies that processDebugManifest actually transforms the manifest on AGP 9.0,
     * replacing the application class via the androidComponents artifact transform path.
     */
    @Test
    fun generatedScript_shouldRunManifestTaskOnAgp90WithInjectApplicationEnabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp90",
            task = ":app:processDebugManifest",
            extraArgs = listOf("-P${PARAM_INJECT_ENABLE}=true"),
        )
        assertEquals(0, result.exitCode, "Gradle $gradleVersion AGP 9.0 manifest task failed.\n${result.output}")
        assertTrue(
            result.output.contains("Jugg manifestTask replace application variant"),
            "Expected manifest replacement log not found.\n${result.output}",
        )
    }
}

/** Mirrors GradleApplicationInjector.PARAM_ENABLE without pulling in the production class. */
private const val PARAM_INJECT_ENABLE = "jugg.inject.application.enable"
