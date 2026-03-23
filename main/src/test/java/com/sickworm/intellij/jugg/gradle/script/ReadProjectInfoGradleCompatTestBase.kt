package com.sickworm.intellij.jugg.gradle.script

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Base class for testing that readProjectInfo.gradle.kts compiles and runs correctly
 * across different Gradle versions.
 *
 * Subclasses declare [gradleVersion] and implement [buildProjectFiles] to provide
 * version-specific fixture content (settings.gradle, build.gradle files).
 */
abstract class ReadProjectInfoGradleCompatTestBase {

    protected abstract val gradleVersion: String

    /** Writes all project files (settings, build scripts) except the Gradle wrapper. */
    protected abstract fun buildProjectFiles(projectDir: File)

    /**
     * Verifies that the init script compiles and runs successfully on [gradleVersion],
     * and that the project info file is written with at least the module list.
     */
    protected fun assertInitScriptRunsAndCollectsProjectInfo() {
        val fixtureDir = Files.createTempDirectory("jugg_gradle_fixture_${gradleVersion.replace('.', '_')}").toFile()
        try {
            buildProjectFiles(fixtureDir)
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)

            val result = runGradle(
                fixtureDir,
                "help",
                "-I",
                initScript.absolutePath,
                "--stacktrace",
                "--console=plain",
                "--no-daemon",
            )

            assertEquals(0, result.exitCode, "Gradle $gradleVersion failed.\n${result.output}")

            val outputFile = JuggPathManager(fixtureDir).gradleProjectInfoFile
            assertTrue(outputFile.exists(), "project info file missing. output=\n${result.output}")

            val projectInfo = ProjectInfoSerializer(outputFile, mock(Logger::class.java)).load(isSkipVersionCheck = true)
            assertNotNull(projectInfo)
            assertTrue(projectInfo.modules.containsKey("app"), "module 'app' missing. output=\n${result.output}")
            assertTrue(projectInfo.modules.containsKey("lib"), "module 'lib' missing. output=\n${result.output}")
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    /**
     * Copies a pre-built Android fixture from src/test/assets/[assetDir] into a temp dir,
     * writes the Gradle wrapper for [gradleVersion], injects the init script, and runs
     * `./gradlew [task]` with the given extra args (e.g. `-Pjugg.inject.application.enable=true`).
     *
     * Use [task] = "help" (default) to only test the configuration phase.
     * Use [task] = ":app:processDebugManifest" to exercise the execution phase and trigger
     * code paths inside `doLast` blocks, e.g. [InitScriptManifestXmlHelper] instantiation.
     *
     * The fixture must contain a valid Android application project. A `local.properties` file
     * with the current machine's sdk.dir is written automatically.
     *
     * Returns the Gradle process result.
     */
    protected fun assertInitScriptRunsOnAndroidFixture(
        assetDir: String,
        task: String = "help",
        extraArgs: List<String> = emptyList(),
    ): ProcessResult {
        val assetSource = File(System.getProperty("user.dir"), "src/test/assets/$assetDir")
        val fixtureDir = Files.createTempDirectory("jugg_android_fixture_${gradleVersion.replace('.', '_')}").toFile()
        try {
            assetSource.copyRecursively(fixtureDir, overwrite = true)
            writeSdkLocalProperties(fixtureDir)
            writeWrapper(fixtureDir, gradleVersion)
            val initScript = copyGeneratedInitScript(fixtureDir)

            val result = runGradle(
                fixtureDir,
                task,
                "-I", initScript.absolutePath,
                "--stacktrace",
                "--console=plain",
                "--no-daemon",
                *extraArgs.toTypedArray(),
            )
            return result
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    protected fun writeWrapper(projectDir: File, version: String) {
        val gradlewSource = File("../gradlew").absoluteFile.normalize()
        val gradleWrapperJarSource = File("../gradle/wrapper/gradle-wrapper.jar").absoluteFile.normalize()
        val gradleWrapperPropertiesSource = File("../gradle/wrapper/gradle-wrapper.properties").absoluteFile.normalize()

        val targetGradlew = File(projectDir, "gradlew")
        gradlewSource.copyTo(targetGradlew, overwrite = true)
        targetGradlew.setExecutable(true)

        val wrapperDir = File(projectDir, "gradle/wrapper")
        wrapperDir.mkdirs()
        gradleWrapperJarSource.copyTo(File(wrapperDir, "gradle-wrapper.jar"), overwrite = true)
        val properties = gradleWrapperPropertiesSource.readText()
            .replace(Regex("distributionUrl=.*"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-${version}-bin.zip")
        File(wrapperDir, "gradle-wrapper.properties").writeText(properties)
    }

    protected fun copyGeneratedInitScript(projectDir: File): File {
        val scriptText = javaClass.getResource("/gradle/readProjectInfo.gradle.kts")?.readText()
        assertNotNull(scriptText)
        val initScript = File(projectDir, "readProjectInfo.gradle.kts")
        initScript.writeText(scriptText)
        return initScript
    }

    protected fun createMinimalJar(file: File) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\n".toByteArray())
            zos.closeEntry()
        }
    }

    protected fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun writeSdkLocalProperties(projectDir: File) {
        val sdkDir = System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
            ?: "/Users/wormchen/Library/Android/sdk"
        File(projectDir, "local.properties").writeText("sdk.dir=$sdkDir\n")
    }

    /**
     * The JAVA_HOME passed to the child Gradle process.
     * Subclasses may override this to use a different JDK version (e.g. for Gradle 5.x compat tests).
     */
    protected open val javaHomeForChildProcess: String
        get() = System.getProperty("java.home")

    protected fun runGradle(projectDir: File, vararg args: String): ProcessResult {
        val output = ByteArrayOutputStream()
        val process = ProcessBuilder(listOf(File(projectDir, "gradlew").absolutePath) + args)
            .directory(projectDir)
            .redirectErrorStream(true)
            .apply {
                environment()["JAVA_HOME"] = javaHomeForChildProcess
                environment()["GRADLE_USER_HOME"] = File(projectDir, ".gradle-user-home").absolutePath
            }
            .start()
        process.inputStream.copyTo(output)
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, output.toString())
    }

    protected data class ProcessResult(val exitCode: Int, val output: String)
}
