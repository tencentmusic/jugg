package com.sickworm.intellij.jugg.gradle.script

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun generatedScript_shouldCollectRuntimeOnlyLibraryWhenAnotherAppHasNoVariants() {
        assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            task = ":app:assembleDebug",
            extraArgs = listOf("--dry-run"),
            beforeRun = { fixtureDir ->
                writeRuntimeOnlyMavenFixture(fixtureDir)
                File(fixtureDir, "settings.gradle").appendText("\ninclude ':inactive_app', ':inactive_broken_app'\n")
                val inactiveAppBuild = """
                    apply plugin: 'com.android.application'
                    android {
                        compileSdkVersion 33
                        variantFilter { it.ignore = true }
                    }
                """.trimIndent()
                writeFile(File(fixtureDir, "inactive_app/build.gradle"), inactiveAppBuild)
                writeFile(File(fixtureDir, "inactive_broken_app/build.gradle"), inactiveAppBuild + "\n" + """
                    configurations { debugRuntimeClasspath }
                    dependencies { debugRuntimeClasspath files('fixture.jar') }
                    configurations.debugRuntimeClasspath.incoming.beforeResolve {
                        throw new GradleException('Fixture runtime resolution failed')
                    }
                """.trimIndent())
            },
            afterRun = { fixtureDir, result ->
                assertEquals(0, result.exitCode, "Gradle $gradleVersion android fixture failed.\n${result.output}")
                assertFalse(
                    result.output.contains("NoSuchMethodException") && result.output.contains(".isMinifyEnabled()"),
                    "Legacy variant minify collection must not print expected reflection failures.\n${result.output}",
                )
                val projectInfo = ProjectInfoSerializer(
                    JuggPathManager(fixtureDir).gradleProjectInfoFile,
                    mock(Logger::class.java),
                ).load(isSkipVersionCheck = true)
                assertTrue(projectInfo != null, "Project snapshot was not written. output=\n${result.output}")
                for (name in listOf("inactive_app", "inactive_broken_app")) {
                    val inactiveApp = projectInfo.modules.getValue(name)
                    assertTrue(inactiveApp.variants.isEmpty())
                    assertTrue(inactiveApp.runtimeLibraryDependencies.isEmpty())
                }
                val appModule = projectInfo.modules.getValue("app")
                assertFalse(
                    appModule.libraryDependencies.any { it.name == RUNTIME_LIBRARY },
                    "Runtime-scoped transitive library must not be in compile dependencies.",
                )
                assertTrue(
                    appModule.runtimeLibraryDependencies.any { it.name == RUNTIME_LIBRARY },
                    "Runtime-scoped transitive library is missing. output=\n${result.output}",
                )
            },
        )
    }

    @Test
    fun generatedScript_shouldCollectLibraryRuntimeDependenciesForCompileOnlyDetection() {
        assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            task = ":app:assembleDebug",
            extraArgs = listOf("--dry-run"),
            beforeRun = { fixtureDir ->
                File(fixtureDir, "settings.gradle").appendText("\ninclude ':lib'\n")
                File(fixtureDir, "app/build.gradle").appendText("\ndependencies { implementation project(':lib') }\n")
                val libDir = File(fixtureDir, "lib")
                writeFile(File(libDir, "src/main/AndroidManifest.xml"), """<manifest package="com.example.lib"/>""")
                File(libDir, "runtime_lib.jar").writeBytes(byteArrayOf())
                File(libDir, "framework_stub.jar").writeBytes(byteArrayOf())
                writeFile(File(libDir, "build.gradle"), """
                    apply plugin: 'com.android.library'
                    android {
                        compileSdkVersion 33
                        defaultConfig { minSdkVersion 21 }
                    }
                    dependencies {
                        implementation files('runtime_lib.jar')
                        compileOnly files('framework_stub.jar')
                    }
                """.trimIndent())
            },
            afterRun = { fixtureDir, result ->
                assertEquals(0, result.exitCode, "Gradle $gradleVersion android fixture failed.\n${result.output}")
                val projectInfo = ProjectInfoSerializer(
                    JuggPathManager(fixtureDir).gradleProjectInfoFile,
                    mock(Logger::class.java),
                ).load(isSkipVersionCheck = true)
                assertTrue(projectInfo != null, "Project snapshot was not written. output=\n${result.output}")
                val libModule = projectInfo.modules.getValue("lib")
                assertTrue(
                    libModule.libraryDependencies.any { it.file.name == "framework_stub.jar" },
                    "Library compile dependencies must include compileOnly framework_stub.jar.",
                )
                assertTrue(
                    libModule.runtimeLibraryDependencies.any { it.file.name == "runtime_lib.jar" },
                    "Library runtime dependencies must include implementation runtime_lib.jar.",
                )
                assertFalse(
                    libModule.runtimeLibraryDependencies.any { it.file.name == "framework_stub.jar" },
                    "Library runtime dependencies must exclude compileOnly framework_stub.jar.",
                )
            },
        )
    }

    @Test
    fun generatedScript_shouldNotWriteSnapshotWhenActiveAppRuntimeResolutionFails() {
        assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            task = ":app:assembleDebug",
            extraArgs = listOf("--dry-run"),
            beforeRun = { fixtureDir ->
                File(fixtureDir, "app/build.gradle").appendText("\n" + """
                    dependencies { runtimeOnly files('fixture.jar') }
                    configurations.configureEach {
                        if (name == 'debugRuntimeClasspath') {
                            incoming.beforeResolve {
                                throw new GradleException('Fixture runtime resolution failed')
                            }
                        }
                    }
                """.trimIndent())
            },
            afterRun = { fixtureDir, result ->
                assertEquals(0, result.exitCode, result.output)
                assertTrue(result.output.contains("Fixture runtime resolution failed"), result.output)
                assertFalse(JuggPathManager(fixtureDir).gradleProjectInfoFile.exists(), result.output)
            },
        )
    }

    /**
     * Verifies the legacy `applicationVariants` path records the resolved minify flag of every
     * variant, so the incremental compiler can tell a minified variant apart from one that only has
     * a leftover `outputs/mapping/<variant>/mapping.txt`.
     *
     * Only the configuration phase runs here, so flipping the release flag costs no R8 build.
     */
    @Test
    fun generatedScript_shouldCollectVariantMinifyFlagsFromLegacyVariants() {
        assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            beforeRun = { fixtureDir ->
                val appBuildFile = File(fixtureDir, "app/build.gradle")
                appBuildFile.writeText(appBuildFile.readText().replace("minifyEnabled false", "minifyEnabled true"))
            },
            afterRun = { fixtureDir, result ->
                assertEquals(0, result.exitCode, "Gradle $gradleVersion android fixture failed.\n${result.output}")
                val projectInfo = ProjectInfoSerializer(
                    JuggPathManager(fixtureDir).gradleProjectInfoFile,
                    mock(Logger::class.java),
                ).load(isSkipVersionCheck = true)
                val variants = projectInfo!!.modules.getValue("app").variants
                assertEquals(
                    mapOf("debug" to false, "release" to true),
                    variants.associate { it.name to it.minifyEnabled },
                    "Legacy variants must carry the minify flag read from the variant build type.\n${result.output}",
                )
            },
        )
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

    @Test
    fun generatedScript_shouldSkipRuntimeInjectionWhenDisabled() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp7",
            task = ":app:processDebugManifest",
            extraArgs = listOf(
                "-P${PARAM_INJECT_ENABLE}=false",
            ),
        ) { fixtureDir, gradleResult ->
            assertEquals(0, gradleResult.exitCode, "Gradle $gradleVersion manifest task failed.\n${gradleResult.output}")
            val manifests = File(fixtureDir, "app/build/intermediates")
                .walkTopDown()
                .filter { it.name == "AndroidManifest.xml" }
                .map { it.readText() }
                .toList()
            assertTrue(manifests.isNotEmpty(), "merged manifest not found.\n${gradleResult.output}")
            assertFalse(
                manifests.any { it.contains("com.sickworm.intellij.jugg.hotfix.BootstrapApplication") },
                "Disabled runtime injection must not inject BootstrapApplication.\n${gradleResult.output}",
            )
            assertFalse(
                manifests.any { it.contains("com.sickworm.intellij.jugg.hotfix.raw.application") },
                "Disabled runtime injection must not inject raw application metadata.\n${gradleResult.output}",
            )
        }
        assertEquals(0, result.exitCode, "Gradle $gradleVersion manifest task failed.\n${result.output}")
        assertTrue(
            result.output.contains("Jugg: injectApplication is not enable, ignore"),
            "Gradle output should explain why runtime injection was skipped.\n${result.output}",
        )
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
                buildDir = new File(rootProject.projectDir, "build/${'$'}{project.name}")
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

    private fun writeRuntimeOnlyMavenFixture(projectDir: File) {
        val repositoryDir = File(projectDir, "repo/com/example")
        val directDir = File(repositoryDir, "direct-lib/1.0")
        val runtimeDir = File(repositoryDir, "runtime-lib/1.0")
        createMinimalJar(File(directDir, "direct-lib-1.0.jar"))
        createMinimalJar(File(runtimeDir, "runtime-lib-1.0.jar"))
        writeFile(
            File(directDir, "direct-lib-1.0.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>direct-lib</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>runtime-lib</artifactId>
                        <version>1.0</version>
                        <scope>runtime</scope>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent(),
        )
        writeFile(
            File(runtimeDir, "runtime-lib-1.0.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>runtime-lib</artifactId>
                <version>1.0</version>
            </project>
            """.trimIndent(),
        )
        File(projectDir, "build.gradle").appendText(
            "\nallprojects { repositories { maven { url uri(rootProject.file('repo')) } } }\n",
        )
        File(projectDir, "app/build.gradle").appendText(
            "\ndependencies { implementation 'com.example:direct-lib:1.0' }\n",
        )
    }
}

/** Mirrors GradleApplicationInjector.PARAM_ENABLE without pulling in the production class. */
private const val PARAM_INJECT_ENABLE = "jugg.inject.application.enable"
private const val RUNTIME_LIBRARY = "com.example:runtime-lib:1.0"
