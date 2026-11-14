package com.sickworm.intellij.jugg.deploy.run.applychanges

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.LaunchContext
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File
import java.nio.file.Files

class CustomApkInstallScriptRunnerTest {

    @Test
    fun `direct commands receive positional APK and serial without trailing arguments`() {
        assertArguments("printf \"%s\\0\" \"\$1\" \"\$2\" > arguments")
    }

    @Test
    fun `project scripts automatically receive APK and serial without explicit arguments`() {
        assertArguments("bash install.sh")
    }

    @Test
    fun `compound commands use positional arguments without trailing arguments`() {
        assertArguments("printf \"%s\\0\" \"\$1\" > arguments && printf \"%s\\0\" \"\$2\" >> arguments")
    }

    @Test
    fun `APK commands run in order and stop after the first failure`() {
        assertArguments(
            "printf \"%s\\0\" \"\$1\" \"\$2\" >> arguments",
            listOf("first.apk", "second; split.apk", "must-not-run.apk"), 2,
        )
    }

    @Test
    fun `only serial reference selects positional mode`() {
        assertArguments("bash install.sh \"\$@\"; : \"\$2\"")
    }

    @Test
    fun `script names containing dollar ten still receive appended arguments`() {
        assertArguments("bash 'install\$10.sh'")
    }

    private fun assertArguments(
        command: String,
        apkNames: List<String> = listOf("中文 app's (base); \$HOME.apk"),
        failAt: Int = apkNames.size,
    ) {
        assumeFalse(isWindows)
        TestGlobal.init()
        val root = Files.createTempDirectory("jugg_install_args").toFile()
        val output = File(root, "arguments")
        File(root, "install.sh").writeText("printf '%s\\0' \"\$@\" > arguments")
        File(root, "install\$10.sh").writeText(File(root, "install.sh").readText())
        val apks = apkNames.map { File(root, it).absolutePath }
        val serial = "device-1;echo injected"
        val originalApi = PlatformApi.impl
        val api = Mockito.mock(IPlatformApi::class.java)
        whenever(api.getEnvironmentVariables(any())).thenReturn(emptyMap())
        PlatformApi.impl = api
        try {
            val project = Mockito.mock(Project::class.java)
            whenever(project.basePath).thenReturn(root.path)
            val context = Mockito.mock(LaunchContext::class.java)
            val deviceAdb = Mockito.mock(IDeviceAdb::class.java)
            whenever(context.deviceAdb).thenReturn(deviceAdb)
            whenever(deviceAdb.serial).thenReturn(serial)
            whenever(context.compileUiHandler).thenReturn(Mockito.mock(CompileUiHandler::class.java))
            var executions = 0
            Mockito.mockConstruction(CmdExecutor::class.java) { executor, _ ->
                whenever(executor.invoke(any<SimpleSshCommand>(), anyOrNull(), anyOrNull(), anyOrNull()))
                    .thenAnswer { invocation ->
                        val shellCommand = invocation.getArgument<SimpleSshCommand>(0).baseCommand
                        val process = ProcessBuilder("bash", "--noprofile", "--norc", "-c", shellCommand)
                            .directory(root).start()
                        assertEquals(0, process.waitFor())
                        if (++executions == failAt) 9 else 0 // Stop before device verification.
                    }
            }.use {
                val runner = CustomApkInstallScriptRunner(project, command, context, Mockito.mock(Logger::class.java))
                assertThrows(IllegalStateException::class.java) { runner.run("com.example.app", apks) }
                assertEquals(apks.take(failAt).flatMap { listOf(it, serial) } + "", output.readText().split('\u0000'))
            }
        } finally {
            PlatformApi.impl = originalApi
            root.deleteRecursively()
        }
    }

    @Test
    fun `script path includes Android SDK platform tools and preserves IDE environment`() {
        val androidHome = Files.createTempDirectory("jugg_android_home").toFile()
        val platformTools = File(androidHome, "platform-tools").apply { mkdirs() }
        try {
            val environment = CustomApkInstallScriptRunner.withAndroidSdkPlatformTools(listOf(
                "PATH=/usr/bin",
                "ANDROID_HOME=${androidHome.path}",
                "CUSTOM_VALUE=kept",
            )).associate { it.substringBefore('=') to it.substringAfter('=') }

            assertEquals(
                listOf(platformTools.path, "/usr/bin"),
                environment.getValue("PATH").split(File.pathSeparator),
            )
            assertEquals("kept", environment["CUSTOM_VALUE"])
        } finally {
            androidHome.deleteRecursively()
        }
    }
}
