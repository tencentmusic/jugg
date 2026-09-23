package com.sickworm.intellij.jugg.gradle.script

import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.MultiDexFileReader
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import org.mockito.Mockito.mock
import org.junit.Assume
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
    fun generatedScript_shouldUseSelectedVariantMinSdk() {
        assertSelectedVariantMinSdk("android-app-agp90")
    }

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
    fun generatedScript_shouldUseResolvedRuntimeModulesAfterExcludes() {
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_runtime_modules").toFile()
        try {
            File(System.getProperty("user.dir"), "src/test/assets/android-app-agp9")
                .copyRecursively(fixtureDir, overwrite = true)
            File(fixtureDir, "settings.gradle").appendText(
                """

                include ':feature', ':mlive'
                includeBuild('business_gift') {
                    dependencySubstitution {
                        substitute module('com.tme.rif:business_gift') using project(':')
                    }
                }
                """.trimIndent(),
            )
            val rootBuildFile = File(fixtureDir, "build.gradle")
            rootBuildFile.writeText(
                rootBuildFile.readText().replace(
                    "id 'com.android.application' version '8.7.2' apply false",
                    """
                    id 'com.android.application' version '8.7.2' apply false
                    id 'com.android.dynamic-feature' version '8.7.2' apply false
                    id 'com.android.library' version '8.7.2' apply false
                    """.trimIndent(),
                )
            )
            File(fixtureDir, "app/build.gradle").appendText(
                """

                android.dynamicFeatures = [':feature']
                dependencies {
                    implementation(project(':mlive')) {
                        exclude group: 'com.tme.rif'
                    }
                }
                """.trimIndent(),
            )
            writeFile(
                File(fixtureDir, "mlive/build.gradle"),
                """
                plugins { id 'com.android.library' }
                android {
                    namespace 'com.jugg.fixture.mlive'
                    compileSdk 35
                    defaultConfig { minSdk 21 }
                }
                dependencies {
                    implementation 'com.tme.rif:business_gift:1.0'
                }
                """.trimIndent(),
            )
            writeFile(
                File(fixtureDir, "feature/build.gradle"),
                """
                plugins { id 'com.android.dynamic-feature' }
                android {
                    namespace 'com.jugg.fixture.feature'
                    compileSdk 35
                    defaultConfig { minSdk 21 }
                }
                dependencies {
                    implementation project(':app')
                    implementation project(':mlive')
                }
                """.trimIndent(),
            )
            writeFile(
                File(fixtureDir, "business_gift/settings.gradle"),
                """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                rootProject.name = 'business_gift'
                """.trimIndent(),
            )
            writeFile(
                File(fixtureDir, "business_gift/build.gradle"),
                """
                plugins { id 'com.android.library' version '8.7.2' }
                group = 'com.tme.rif'
                version = '1.0'
                android {
                    namespace 'com.tme.rif.business.gift'
                    compileSdk 35
                    defaultConfig { minSdk 21 }
                }
                """.trimIndent(),
            )
            writeSdkLocalProperties(fixtureDir)
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)

            val result = runGradle(
                fixtureDir,
                "help",
                "-I", initScript.absolutePath,
                "--console=plain",
                "--no-daemon",
            )

            assertEquals(0, result.exitCode, "Gradle $gradleVersion runtime dependency check failed.\n${result.output}")
            val projectInfo = ProjectInfoSerializer(
                JuggPathManager(fixtureDir).gradleProjectInfoFile,
                mock(Logger::class.java),
            ).load(isSkipVersionCheck = true)
            assertNotNull(projectInfo)
            val appRuntimeModules = projectInfo.modules.getValue("app").runtimeModuleDependencies
            val featureRuntimeModules = projectInfo.modules.getValue("feature").runtimeModuleDependencies
            assertTrue(appRuntimeModules.orEmpty().any { it.moduleName == "mlive" })
            assertFalse(appRuntimeModules.orEmpty().any { it.moduleName == "business_gift" })
            assertTrue(featureRuntimeModules.orEmpty().any { it.moduleName == "business_gift" })
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
            assertTrue(apkContainsClass(apkFile, BOOTSTRAP_APPLICATION))
            assertTrue(apkContainsClass(apkFile, BOOTSTRAP_APP_COMPONENT_FACTORY))
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    @Test
    fun generatedScript_shouldMinifyReleaseWithBundledRuntime() {
        val fixtureDir = Files.createTempDirectory("jugg_runtime_release_minify").toFile()
        try {
            File(System.getProperty("user.dir"), "src/test/assets/android-app-agp9")
                .copyRecursively(fixtureDir, overwrite = true)
            val buildFile = File(fixtureDir, "build.gradle")
            buildFile.writeText(buildFile.readText().replace("8.7.2", "8.9.1"))
            val appBuildFile = File(fixtureDir, "app/build.gradle")
            appBuildFile.writeText(
                appBuildFile.readText().replace(
                    "minifyEnabled false",
                    "minifyEnabled true\n            proguardFiles 'proguard-rules.pro'",
                ),
            )
            writeFile(
                File(fixtureDir, "app/proguard-rules.pro"),
                "-keep class com.sickworm.intellij.jugg.internal.dragonfly.node.WalkerSequence\$WalkerIterator { *; }",
            )
            writeSdkLocalProperties(fixtureDir)
            writeWrapper(fixtureDir, "8.11.1")
            val initScript = copyGeneratedInitScript(fixtureDir)
            val runtimeJar = File(fixtureDir, ".gradle/jugg/jugg-runtime.jar")
            runtimeJar.parentFile.mkdirs()
            javaClass.getResourceAsStream("/deploy/jugg-runtime.jar")!!.use { input ->
                runtimeJar.outputStream().use(input::copyTo)
            }

            val result = runGradle(
                fixtureDir,
                ":app:assembleRelease",
                "-I", initScript.absolutePath,
                "-P${PARAM_INJECT_ENABLE}=true",
                "-Pjugg.projectDir=${fixtureDir.absolutePath}",
                "--stacktrace",
                "--console=plain",
                "--no-build-cache",
            )

            assertEquals(0, result.exitCode, "Jugg release minify build failed.\n${result.output}")
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

    @Test
    fun generatedScript_shouldCollectApplicationAndLibraryVariantsOnAgp90() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp90",
            task = ":app:assembleRelease",
            // The minify flag is read from the real AGP variant, so one variant must differ from the
            // other for the assertion below to prove the value comes from the build configuration.
            // Debug is not assembled here, which keeps this fixture free of an R8 run.
            beforeRun = { fixtureDir ->
                val appBuildFile = File(fixtureDir, "app/build.gradle")
                appBuildFile.writeText(appBuildFile.readText().replace("debug {}", "debug { minifyEnabled true }"))
            },
        ) { fixtureDir, gradleResult ->
            assertEquals(0, gradleResult.exitCode, gradleResult.output)
            val outputFile = JuggPathManager(fixtureDir).gradleProjectInfoFile
            val projectInfo = ProjectInfoSerializer(outputFile, mock(Logger::class.java))
                .load(isSkipVersionCheck = true)
            assertNotNull(projectInfo)
            val app = projectInfo.modules.getValue("app")
            val library = projectInfo.modules.getValue("library1")
            assertEquals(listOf("debug", "release"), app.variants.map { it.name }.sorted())
            assertEquals(listOf("debug", "release"), library.variants.map { it.name }.sorted())
            assertEquals("release", app.buildVariant)
            assertEquals("release", library.buildVariant)
            assertEquals(
                mapOf("debug" to true, "release" to false),
                app.variants.associate { it.name to it.minifyEnabled },
                "AGP 9 variants must carry the minify flag read from the variant API.\n${gradleResult.output}",
            )
            assertEquals(
                mapOf("debug" to false, "release" to false),
                library.variants.associate { it.name to it.minifyEnabled },
                "AGP 9 library variants must carry the minify flag read from the variant API.\n${gradleResult.output}",
            )
            assertTrue(app.moduleDependencies.any { it.moduleName == library.name })
        }
        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun generatedScript_shouldInjectReleaseAndroidTestTaskOnAgp90() {
        val result = assertInitScriptRunsOnAndroidFixture(
            assetDir = "android-app-agp90",
            task = ":app:assembleRelease",
            extraArgs = listOf("-Pjugg.buildTarget=ANDROID_TEST"),
        )
        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains(":app:assembleReleaseAndroidTest"), result.output)
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

    /**
     * Verifies the selective native strip contract against a real AGP project: the collector runs
     * the requested merge task before stripping its output, without executing the app strip task,
     * and reproduces AGP's own stripped output byte for byte.
     */
    @Test
    fun generatedScript_shouldBuildBeforeStrippingSelectedNativeOutput() {
        assumeNativeToolchain()
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_native_strip").toFile()
        try {
            File(System.getProperty("user.dir"), "src/test/assets/android-app-native")
                .copyRecursively(fixtureDir, overwrite = true)
            createMinimalJar(File(fixtureDir, ".gradle/jugg/jugg-runtime.jar"))
            writeSdkLocalProperties(fixtureDir)
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)
            // Gradle resolves its project directories, so the request must use the same real paths.
            val appDir = File(fixtureDir, "app").canonicalFile

            // The merge task also writes the project info the collector reads afterwards.
            val mergeResult = runGradle(
                fixtureDir,
                ":app:mergeDebugNativeLibs",
                "-I", initScript.absolutePath,
                "--console=plain",
                "--no-daemon",
            )
            assertEquals(0, mergeResult.exitCode, "Fixture native build failed.\n${mergeResult.output}")
            val nativeSource = File(fixtureDir, "app/src/main/cpp/native.cpp")
            nativeSource.writeText(nativeSource.readText().replace("jugg-fixture", "jugg-fixture-updated"))

            val invocationDir = File(fixtureDir, "invocation")
            val requestFile = File(invocationDir, "request.json").apply {
                parentFile.mkdirs()
                writeText(
                    """{"invocationId":"invocation-1","items":[{"moduleName":"app",""" +
                            """"moduleRootDir":"${appDir.path}","buildVariant":"debug",""" +
                            """"taskPath":":app:mergeDebugNativeLibs","type":"Cpp",""" +
                            """"apkOwnerModuleRootDir":"${appDir.path}","apkOwnerBuildVariant":"debug"}]}""",
                )
            }
            val outputDir = File(invocationDir, "output")
            val collectResult = runGradle(
                fixtureDir,
                GradleProjectInfoReaderManager.COLLECT_EXTERNAL_BUILD_INFO_TASK_PATH,
                "-I", initScript.absolutePath,
                "-P${GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_REQUEST}=${requestFile.path}",
                "-P${GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_OUTPUT}=${outputDir.path}",
                "-P${GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_INVOCATION}=invocation-1",
                "--console=plain",
                "--no-daemon",
            )
            assertEquals(0, collectResult.exitCode, "Collector failed.\n${collectResult.output}")
            assertTrue(
                collectResult.output.contains("> Task :app:mergeDebugNativeLibs"),
                "The collector must run the requested module merge task first.\n${collectResult.output}",
            )
            assertFalse(
                collectResult.output.contains("> Task :app:stripDebugDebugSymbols"),
                "The collector must not execute the app strip task.\n${collectResult.output}",
            )

            val update = readSingleUpdate(outputDir)
            val strippedRoot = File(update["strippedNativeOutput"] as String)
            val strippedLibs = strippedRoot.walkTopDown().filter { it.extension == "so" }.toList()
            assertEquals(1, strippedLibs.size, "stripped libs: $strippedLibs")
            assertEquals("arm64-v8a", strippedLibs.single().parentFile.name, "compiled output layout changed")

            val agpStripResult = runGradle(
                fixtureDir,
                ":app:stripDebugDebugSymbols",
                "--console=plain",
                "--no-daemon",
            )
            assertEquals(0, agpStripResult.exitCode, "AGP strip failed.\n${agpStripResult.output}")
            val agpStripped = File(fixtureDir, "app/build/intermediates/stripped_native_libs")
                .walkTopDown()
                .single { it.isFile && it.name == strippedLibs.single().name }
            assertEquals(
                agpStripped.readBytes().toList(),
                strippedLibs.single().readBytes().toList(),
                "Jugg stripped output must match the AGP strip output",
            )
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    /**
     * Verifies the configuration-on-demand regressions of reports 75046b19 and 584a6a17 against a
     * real AGP project:
     * the native build lives in a library module, the APK owner `:app` is not part of the external
     * invocation, and the collector still strips the library output from the configuration a previous
     * full Gradle build cached.
     */
    @Test
    fun generatedScript_shouldStripNativeOutputWhenApkOwnerIsNotConfigured() {
        assumeNativeToolchain()
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_native_strip_ondemand").toFile()
        try {
            File(System.getProperty("user.dir"), "src/test/assets/android-app-native-ondemand")
                .copyRecursively(fixtureDir, overwrite = true)
            createMinimalJar(File(fixtureDir, ".gradle/jugg/jugg-runtime.jar"))
            writeSdkLocalProperties(fixtureDir)
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)
            // Gradle resolves its project directories, so the request must use the same real paths.
            val appDir = File(fixtureDir, "app").canonicalFile
            val libDir = File(fixtureDir, "nativelib").canonicalFile

            val fullBuild = runGradle(
                fixtureDir,
                ":app:assembleDebug",
                "-I", initScript.absolutePath,
                "--console=plain",
                "--no-daemon",
            )
            assertEquals(0, fullBuild.exitCode, "Full Gradle build failed.\n${fullBuild.output}")
            val cacheDir = File(fixtureDir, "build/jugg/classpath/native_strip")
            assertTrue(
                File(cacheDir, "config.json").isFile,
                "A full Gradle build must cache the APK owner strip configuration.\n${fullBuild.output}",
            )
            // Force the requested native merge task to produce a different library for this invocation.
            val nativeSource = File(fixtureDir, "nativelib/src/main/cpp/native.cpp")
            nativeSource.writeText(
                nativeSource.readText().replace(
                    "jugg-ondemand-fixture",
                    "jugg-ondemand-fixture-updated",
                ),
            )

            val invocationDir = File(fixtureDir, "invocation")
            val requestFile = File(invocationDir, "request.json").apply {
                parentFile.mkdirs()
                writeText(
                    """{"invocationId":"invocation-1","items":[{"moduleName":"nativelib",""" +
                            """"moduleRootDir":"${libDir.path}","buildVariant":"debug",""" +
                            """"taskPath":":nativelib:mergeDebugNativeLibs","type":"Cpp",""" +
                            """"apkOwnerModuleRootDir":"${appDir.path}","apkOwnerBuildVariant":"debug"}]}""",
                )
            }
            val outputDir = File(invocationDir, "output")
            val collectResult = runGradle(
                fixtureDir,
                ":nativelib:mergeDebugNativeLibs",
                GradleProjectInfoReaderManager.COLLECT_EXTERNAL_BUILD_INFO_TASK_PATH,
                "-I", initScript.absolutePath,
                "-P${GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_REQUEST}=${requestFile.path}",
                "-P${GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_OUTPUT}=${outputDir.path}",
                "-P${GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_INVOCATION}=invocation-1",
                "--parallel",
                "--console=plain",
                "--no-daemon",
            )
            assertEquals(
                0,
                collectResult.exitCode,
                "The collector must strip native output without configuring the APK owner.\n${collectResult.output}",
            )
            assertFalse(
                collectResult.output.contains("> Task :app:stripDebugDebugSymbols") ||
                        collectResult.output.contains("> Task :app:mergeDebugNativeLibs"),
                "The collector must not execute the app strip or app merge task.\n${collectResult.output}",
            )

            val update = readSingleUpdate(outputDir)
            val strippedRoot = File(update["strippedNativeOutput"] as String)
            val strippedLibs = strippedRoot.walkTopDown().filter { it.extension == "so" }.toList()
            assertEquals(1, strippedLibs.size, "stripped libs: $strippedLibs")
            assertEquals("arm64-v8a", strippedLibs.single().parentFile.name, "compiled output layout changed")
            val agpStripResult = runGradle(
                fixtureDir,
                ":app:stripDebugDebugSymbols",
                "--rerun-tasks",
                "--console=plain",
                "--no-daemon",
            )
            assertEquals(0, agpStripResult.exitCode, "AGP strip failed.\n${agpStripResult.output}")
            val agpStripped = File(fixtureDir, "app/build/intermediates/stripped_native_libs")
                .walkTopDown()
                .single { it.isFile && it.name == strippedLibs.single().name }
            val expectedBytes = agpStripped.readBytes()
            val actualBytes = strippedLibs.single().readBytes()
            assertTrue(
                expectedBytes.contentEquals(actualBytes),
                "Jugg stripped output must match the current AGP strip output: " +
                        "expected=${expectedBytes.size}/${expectedBytes.contentHashCode()}, " +
                        "actual=${actualBytes.size}/${actualBytes.contentHashCode()}\n${collectResult.output}",
            )
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    /** The native fixture needs the pinned NDK and a CMake installation next to the Android SDK. */
    private fun assumeNativeToolchain() {
        val sdkDir = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        Assume.assumeTrue("ANDROID_SDK_ROOT or ANDROID_HOME is required", sdkDir != null)
        Assume.assumeTrue(
            "NDK 27.0.12077973 is required by the native fixture",
            File(sdkDir, "ndk/27.0.12077973").isDirectory,
        )
        Assume.assumeTrue(
            "CMake is required by the native fixture",
            File(sdkDir, "cmake").listFiles().orEmpty().isNotEmpty(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSingleUpdate(outputDir: File): Map<String, Any> {
        val resultFile = outputDir.listFiles().orEmpty().single { it.extension == "json" }
        val resultJson = groovy.json.JsonSlurper().parse(resultFile) as Map<String, Any>
        return (resultJson["updates"] as List<Map<String, Any>>).single()
    }
}

/** Mirrors GradleApplicationInjector.PARAM_ENABLE without pulling in the production class. */
private const val PARAM_INJECT_ENABLE = "jugg.inject.application.enable"
private const val BOOTSTRAP_APPLICATION = "Lcom/sickworm/intellij/jugg/hotfix/BootstrapApplication;"
private const val BOOTSTRAP_APP_COMPONENT_FACTORY = "Lcom/sickworm/intellij/jugg/hotfix/BootstrapAppComponentFactory;"
