package com.sickworm.intellij.jugg.gradle.script

import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.MultiDexFileReader
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

    @Test
    fun generatedScript_shouldCollectIncludedBuildProjectInfo() {
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_9_2_1_collect_include_build").toFile()
        try {
            buildProjectFiles(fixtureDir)
            File(fixtureDir, "settings.gradle").appendText("\nincludeBuild 'SMCommon'\n")
            writeFile(File(fixtureDir, "SMCommon/settings.gradle"), "rootProject.name = 'SMCommon'")
            writeFile(File(fixtureDir, "SMCommon/build.gradle"), "")
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)

            val result = runGradle(
                fixtureDir,
                "help",
                "-I", initScript.absolutePath,
                "-Pjugg.projectDir=${fixtureDir.absolutePath}",
                "--console=plain",
                "--no-daemon",
            )

            assertEquals(0, result.exitCode, "Gradle $gradleVersion included build collection failed.\n${result.output}")
            assertFalse(result.output.contains("Jugg: readProjectInfo.gradle execute failed"), result.output)
            assertFalse(result.output.contains("Jugg: skip missing include build project info"), result.output)
            val includedProjectInfoFile = JuggPathManager(File(fixtureDir, "SMCommon")).gradleProjectInfoFile
            assertTrue(includedProjectInfoFile.exists(), result.output)
            val rootPathManager = JuggPathManager(fixtureDir)
            assertTrue(rootPathManager.gradleIncludeBuildsFile.exists(), result.output)
            val copiedProjectInfoFile = File(rootPathManager.gradleIncludeBuildsFile.readLines().single())
            assertTrue(copiedProjectInfoFile.exists(), result.output)
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    @Test
    fun generatedScript_shouldKeepRootProjectInfoWhenIncludedBuildInfoIsMissing() {
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_9_2_1_include_build").toFile()
        try {
            buildProjectFiles(fixtureDir)
            val pathManager = JuggPathManager(fixtureDir)
            val staleIncludedProjectInfo = File(
                pathManager.gradleIncludeBuildsFile.parentFile,
                "include_build_1_gradle_project_infos.json",
            )
            writeFile(staleIncludedProjectInfo, "stale included build project info")
            writeFile(pathManager.gradleIncludeBuildsFile, staleIncludedProjectInfo.absolutePath)
            File(fixtureDir, "settings.gradle").appendText("\nincludeBuild 'SMCommon'\n")
            File(fixtureDir, "build.gradle").appendText(
                """

                tasks.named('help') {
                    doLast {
                        delete file('SMCommon/build/jugg/database/project_infos.db/gradle_project_infos.json')
                    }
                }
                """.trimIndent(),
            )
            writeFile(File(fixtureDir, "SMCommon/settings.gradle"), "rootProject.name = 'SMCommon'")
            writeFile(File(fixtureDir, "SMCommon/build.gradle"), "")
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)

            val result = runGradle(
                fixtureDir,
                "help",
                "-I", initScript.absolutePath,
                "--console=plain",
                "--no-daemon",
            )

            assertEquals(0, result.exitCode, "Gradle $gradleVersion included build check failed.\n${result.output}")
            assertFalse(result.output.contains("Jugg: readProjectInfo.gradle execute failed"), result.output)
            assertTrue(result.output.contains("Jugg: skip missing include build project info"), result.output)
            assertTrue(pathManager.gradleProjectInfoFile.exists(), result.output)
            val retainedProjectInfo = File(pathManager.gradleIncludeBuildsFile.readLines().single())
            assertEquals(staleIncludedProjectInfo.canonicalFile, retainedProjectInfo.canonicalFile)
            assertEquals("stale included build project info", staleIncludedProjectInfo.readText())
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

    @Test
    fun generatedScript_shouldAddRuntimeClassesAfterNormalBuild() {
        val fixtureDir = Files.createTempDirectory("jugg_runtime_after_normal_build").toFile()
        try {
            File(System.getProperty("user.dir"), "src/test/assets/android-app-agp9")
                .copyRecursively(fixtureDir, overwrite = true)
            val buildFile = File(fixtureDir, "build.gradle")
            buildFile.writeText(buildFile.readText().replace("8.7.2", "8.9.1"))
            val appBuildFile = File(fixtureDir, "app/build.gradle")
            // Simulate a plugin that isolates the variant runtime classpath after Jugg adds runtimeOnly.
            appBuildFile.appendText(
                """

                gradle.projectsEvaluated {
                    def runtimeClasspath = configurations.getByName('debugRuntimeClasspath')
                    runtimeClasspath.setExtendsFrom(
                        runtimeClasspath.extendsFrom.findAll { it.name != 'runtimeOnly' }
                    )
                }
                """.trimIndent(),
            )
            writeSdkLocalProperties(fixtureDir)
            writeWrapper(fixtureDir, "8.11.1")

            val normalBuild = runGradle(
                fixtureDir,
                ":app:assembleDebug",
                "--stacktrace",
                "--console=plain",
                "--build-cache",
            )
            assertEquals(0, normalBuild.exitCode, "Normal Gradle build failed.\n${normalBuild.output}")

            val apkFile = File(fixtureDir, "app/build/outputs/apk/debug/app-debug.apk")
            assertFalse(apkContainsClass(apkFile, BOOTSTRAP_APPLICATION))
            assertFalse(apkContainsClass(apkFile, BOOTSTRAP_APP_COMPONENT_FACTORY))

            val initScript = copyGeneratedInitScript(fixtureDir)
            val runtimeJar = File(fixtureDir, ".gradle/jugg/jugg-runtime.jar")
            runtimeJar.parentFile.mkdirs()
            javaClass.getResourceAsStream("/deploy/jugg-runtime.jar")!!.use { input ->
                runtimeJar.outputStream().use(input::copyTo)
            }
            File(fixtureDir, "app/build").deleteRecursively()
            val juggBuild = runGradle(
                fixtureDir,
                ":app:assembleDebug",
                "-I", initScript.absolutePath,
                "-P${PARAM_INJECT_ENABLE}=true",
                "-Pjugg.projectDir=${fixtureDir.absolutePath}",
                "--stacktrace",
                "--console=plain",
                "--build-cache",
            )
            assertEquals(0, juggBuild.exitCode, "Jugg Gradle build failed.\n${juggBuild.output}")
            assertFalse(
                juggBuild.output.contains(":app:mergeExtDexDebug FROM-CACHE"),
                "Jugg runtime must invalidate the normal-build external dex cache.\n${juggBuild.output}",
            )
            assertTrue(apkContainsClass(apkFile, BOOTSTRAP_APPLICATION))
            assertTrue(apkContainsClass(apkFile, BOOTSTRAP_APP_COMPONENT_FACTORY))
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    private fun apkContainsClass(apkFile: File, className: String): Boolean {
        val dexFileNode = DexFileNode()
        MultiDexFileReader.open(apkFile.readBytes()).accept(dexFileNode)
        return dexFileNode.clzs.any { it.className == className }
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
private const val BOOTSTRAP_APPLICATION = "Lcom/sickworm/intellij/jugg/hotfix/BootstrapApplication;"
private const val BOOTSTRAP_APP_COMPONENT_FACTORY = "Lcom/sickworm/intellij/jugg/hotfix/BootstrapAppComponentFactory;"
