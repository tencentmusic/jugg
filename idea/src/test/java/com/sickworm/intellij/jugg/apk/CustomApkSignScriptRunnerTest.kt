package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

class CustomApkSignScriptRunnerTest {
    @Test
    fun `direct commands receive positional APK without a trailing argument`() {
        assertArguments("printf '%s\\0' \"\$1\" > arguments")
    }

    @Test
    fun `scripts automatically receive the APK argument`() {
        assertArguments("bash sign.sh")
    }

    @Test
    fun `compound commands do not receive an extra APK argument`() {
        assertArguments("printf '%s\\0' \"\$1\" > arguments && printf '' >> arguments")
    }

    @Test
    fun `only dollar two does not select positional mode`() {
        assertArguments("bash 'sign\$2.sh'")
    }

    @Test
    fun `dollar ten does not select positional mode`() {
        assertArguments("bash 'sign\$10.sh'")
    }

    private fun assertArguments(command: String) {
        assumeFalse(isWindows)
        TestGlobal.init()
        val root = Files.createTempDirectory("jugg_sign_args").toFile()
        try {
            val apk = File(root, "中文 app's (base); \$HOME.apk")
            for (name in listOf("sign.sh", "sign\$2.sh", "sign\$10.sh")) {
                File(root, name).writeText("printf '%s\\0' \"\$@\" > arguments")
            }
            Mockito.mockConstruction(CmdExecutor::class.java) { executor, _ ->
                whenever(executor.invoke(any<SimpleSshCommand>(), anyOrNull(), anyOrNull(), anyOrNull()))
                    .thenAnswer { invocation ->
                        val shellCommand = invocation.getArgument<SimpleSshCommand>(0).baseCommand
                        val process = ProcessBuilder("bash", "--noprofile", "--norc", "-c", shellCommand)
                            .directory(root).start()
                        process.waitFor()
                    }
            }.use {
                CustomApkSignScriptRunner(command, root, null, Mockito.mock(CompileUiHandler::class.java),
                    Mockito.mock(Logger::class.java)).run(apk)
                assertEquals(listOf(apk.absolutePath, ""), File(root, "arguments").readText().split('\u0000'))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
