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
    fun `install script receives APK list and current serial as two literal arguments`() {
        assumeFalse(isWindows)
        val root = Files.createTempDirectory("jugg_install_args").toFile()
        val output = File(root, "arguments")
        val script = File(root, "install.sh")
        script.writeText("printf '%s\\0' \"\$@\" > arguments")
        val apks = listOf(File(root, "中文 app's (base).apk"), File(root, "split \$HOME.apk"))
        val serial = "device-1;echo injected"
        TestGlobal.init()
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
            Mockito.mockConstruction(CmdExecutor::class.java) { executor, _ ->
                whenever(executor.invoke(any<SimpleSshCommand>(), anyOrNull(), anyOrNull(), anyOrNull()))
                    .thenAnswer { invocation ->
                        val command = invocation.getArgument<SimpleSshCommand>(0).baseCommand
                        val process = ProcessBuilder("bash", "--noprofile", "--norc", "-c", command)
                            .directory(root).start()
                        assertEquals(0, process.waitFor())
                        9 // Stop before device verification; argv has already been recorded.
                    }
            }.use {
                val runner = CustomApkInstallScriptRunner(project, "bash install.sh", context, Mockito.mock(Logger::class.java))
                assertThrows(IllegalStateException::class.java) {
                    runner.run("com.example.app", apks.map(File::getAbsolutePath))
                }
                assertEquals(listOf(apks.joinToString(";") { it.absolutePath }, serial, ""), output.readText().split('\u0000'))
            }
        } finally {
            PlatformApi.impl = originalApi
            root.deleteRecursively()
        }
    }

    @Test
    fun `install rejects semicolons in APK paths before executing script`() {
        val runner = CustomApkInstallScriptRunner(
            Mockito.mock(Project::class.java), "echo should-not-run",
            Mockito.mock(LaunchContext::class.java), Mockito.mock(Logger::class.java),
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runner.run("com.example.app", listOf("/tmp/app;split.apk"))
        }
        assertEquals("Custom APK install script cannot accept APK paths containing ';'.", failure.message)
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
