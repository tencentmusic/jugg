package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfoRequestItem
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.apache.log4j.Level
import org.mockito.kotlin.mock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalBuildTaskRunnerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `replaces Android build tasks and preserves Gradle arguments`() {
        val command = deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug --offline -Pchannel=demo",
            listOf(":flutter:compileFlutterBuildDebug", ":native:mergeDebugNativeLibs"),
        )

        assertEquals(
            "./gradlew :flutter:compileFlutterBuildDebug :native:mergeDebugNativeLibs --offline -Pchannel=demo",
            command,
        )
    }

    @Test
    fun `rejects compound shell commands`() {
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug && echo done",
            listOf(":flutter:compileFlutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -Pvalue='$(touch injected)'",
            listOf(":flutter:compileFlutterBuildDebug"),
        ))
    }

    @Test
    fun `preserves exclude task values and quoted Gradle arguments`() {
        val command = deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -x :app:compileDebugKotlin -Pmessage=\"hello world\" --offline",
            listOf(":flutter:packJniLibsflutterBuildDebug"),
        )

        assertEquals(
            "./gradlew :flutter:packJniLibsflutterBuildDebug -x :app:compileDebugKotlin -Pmessage=\"hello world\" --offline",
            command,
        )
    }

    @Test
    fun `rejects commands that cannot update external artifacts`() {
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug --dry-run",
            listOf(":flutter:packJniLibsflutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -x :flutter:compileFlutterBuildDebug",
            listOf(":flutter:packJniLibsflutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug -x :flutter:compileFlutterBuildDebug",
            listOf(":flutter:copyJniLibsflutterBuildDebug"),
        ))
        assertNull(deriveExternalBuildCommand(
            "./gradlew :app:assembleDebug --unknown value",
            listOf(":native:mergeDebugNativeLibs"),
        ))
    }

    @Test
    fun `adds the collector task init script and invocation properties`() {
        val initScript = File("/project with spaces/readProjectInfo.gradle.kts")
        val requestFile = File("/tmp/jugg request.json")
        val outputDir = File("/tmp/jugg output")
        val command = deriveExternalBuildCommand(
            compileCommand = "./gradlew :app:assembleDebug --offline",
            taskPaths = listOf(":flutter:packJniLibsflutterBuildDebug"),
            collector = ExternalBuildCollectorCommand(
                initScript = initScript,
                requestFile = requestFile,
                outputDir = outputDir,
                invocationId = "invocation-1",
            ),
        )
        val quote = if (isWindows) "\"" else "'"

        assertEquals(
            "./gradlew :flutter:packJniLibsflutterBuildDebug :juggCollectExternalBuildInfo --offline " +
                    "-I $quote${initScript.absolutePath}$quote " +
                    "-Pjugg.externalBuildRequest=$quote${requestFile.absolutePath}$quote " +
                    "-Pjugg.externalBuildOutput=$quote${outputDir.absolutePath}$quote " +
                    "-Pjugg.externalBuildInvocation=invocation-1",
            command,
        )
    }

    @Test
    fun `reads the invocation scoped collector result after the external task`() {
        val projectDir = temporaryFolder.newFolder("collector-project")
        val moduleRoot = File(projectDir, "app").apply { mkdirs() }
        val inputDir = File(moduleRoot, "native")
        val nativeOutput = File(moduleRoot, "build/new-native")
        val initScript = File(projectDir, "readProjectInfo.gradle.kts").apply { writeText("") }
        File(projectDir, "gradlew").apply {
            writeText("""
                #!/bin/bash
                for argument in "${'$'}@"; do
                    case "${'$'}argument" in
                        -Pjugg.externalBuildOutput=*) output="${'$'}{argument#*=}" ;;
                        -Pjugg.externalBuildInvocation=*) invocation="${'$'}{argument#*=}" ;;
                    esac
                done
                mkdir -p "${'$'}output"
                cat > "${'$'}output/result.json" <<JSON
                {"invocationId":"${'$'}invocation","updates":[{"moduleName":"app","moduleRootDir":"${moduleRoot.path}","buildVariant":"debug","previousTaskPath":":app:mergeDebugNativeLibs","externalBuildInfo":{"type":"Cpp","inputDirs":["${inputDir.path}"],"taskPath":":app:mergeDebugNativeLibs","nativeOutput":"${nativeOutput.path}","configFiles":[],"excludedDirs":[]}}]}
                JSON
            """.trimIndent())
            setExecutable(true)
        }
        val request = ExternalBuildInfoRequestItem(
            moduleName = "app",
            moduleRootDir = moduleRoot,
            buildVariant = "debug",
            taskPath = ":app:mergeDebugNativeLibs",
            type = ExternalBuildType.Cpp,
        )

        val logger = CapturingLogger()
        val result = ExternalBuildTaskRunner(logger).run(
            compileCommand = "./gradlew :app:assembleDebug --offline",
            requests = listOf(request),
            compileEnv = emptyList(),
            projectDir = projectDir,
            metadataRoot = File(projectDir, "metadata"),
            initScript = initScript,
            task = CompileTask(emptyList(), File(projectDir, "output"), CompileStatusHolder.DEFAULT),
        )

        assertTrue(result.isSuccess, logger.messages.joinToString("\n"))
        assertEquals(inputDir, result.updates.single().externalBuildInfo.inputDirs.single())
        assertEquals(nativeOutput, result.updates.single().externalBuildInfo.nativeOutput)
    }

    private class CapturingLogger : Logger() {
        val messages = mutableListOf<String>()

        override fun isDebugEnabled(): Boolean = true
        override fun debug(message: String) {
            messages += message
        }
        override fun debug(t: Throwable?) = Unit
        override fun debug(message: String, t: Throwable?) {
            messages += "$message: $t"
        }
        override fun info(message: String) = Unit
        override fun info(message: String, t: Throwable?) = Unit
        override fun warn(message: String, t: Throwable?) {
            messages += message
        }
        override fun error(message: String, t: Throwable?, vararg details: String?) {
            messages += message
        }
        @Suppress("UnstableApiUsage")
        override fun setLevel(level: Level) = Unit
    }
}
