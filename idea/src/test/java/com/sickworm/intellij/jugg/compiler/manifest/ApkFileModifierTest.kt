package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.apk.CustomApkSignScriptRunner
import com.sickworm.intellij.jugg.apk.manifest.BinaryXmlParser
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkFileModifierTest {

    @Before
    fun before() {
        clearBuild()
    }

    @Test
    fun testUpdateManifest() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")

        val copyApkFile = File(tempCompileDir, "out/${context.apkFile!!.name}")
        copyApkFile.delete()
        context.apkFile!!.copyTo(copyApkFile)
        assertTrue(copyApkFile.exists())
        assertEquals(context.apkFile!!.length(), copyApkFile.length())
        val apkFileModifier = ApkFileModifier(copyApkFile, context.signingConfig, context.androidHome, logger)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())
        apkFileModifier.insertAndResign()
        apkFileModifier.verify()

        val oldManifest = ApkReader(context.apkFile!!, logger).getManifest()
        val manifest = BinaryXmlParser.parseBinaryFromStream(outputFile.inputStream())
        val packageName = manifest.packageName()
        assertEquals(context.packageName, packageName)
        val activities = manifest.activities()
        assertEquals(oldManifest.activities().size + 1, activities.size)
    }

    @Test
    fun testUpdateManifestWithShellCharactersInApkPath() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")
        val copyApkFile = File(tempCompileDir, "out with space(测试)/My App_调试版(228).apk")
        copyApkFile.parentFile.mkdirs()
        context.apkFile!!.copyTo(copyApkFile, overwrite = true)
        val apkFileModifier = ApkFileModifier(copyApkFile, context.signingConfig, context.androidHome, logger)

        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())
        apkFileModifier.insertAndResign()
        apkFileModifier.verify()

        assertTrue(copyApkFile.exists())
    }

    @Test
    fun testSigningFailureKeepsOriginalApk() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")

        val copyApkFile = File(tempCompileDir, "out/${context.apkFile!!.name}")
        copyApkFile.delete()
        context.apkFile!!.copyTo(copyApkFile)
        val originalContent = copyApkFile.readBytes()
        val invalidSigningConfig = context.signingConfig.copy(storePassword = "invalid-password")
        val apkFileModifier = ApkFileModifier(copyApkFile, invalidSigningConfig, context.androidHome, logger)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())

        assertFailsWith<IllegalStateException> {
            apkFileModifier.insertAndResign()
        }

        assertContentEquals(originalContent, copyApkFile.readBytes())
    }

    @Test
    fun testCustomSignScriptSignsApkWithShellCharactersInApkPath() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")
        val copyApkFile = File(tempCompileDir, "out with space(测试)/My App_调试版(228).apk")
        copyApkFile.parentFile.mkdirs()
        context.apkFile!!.copyTo(copyApkFile, overwrite = true)

        val argsFile = File(tempCompileDir, "custom-sign-args.txt")
        val runner = createDemoSignScriptRunner(argsFile)

        // The local signing config is unusable on purpose: only the project script may sign the APK.
        val invalidSigningConfig = context.signingConfig.copy(storePassword = "invalid-password")
        val apkFileModifier = ApkFileModifier(copyApkFile, invalidSigningConfig, context.androidHome, logger, null, runner)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())
        apkFileModifier.insertAndResign()
        apkFileModifier.verify()

        val args = argsFile.readLines()
        assertEquals("1", args[0], "the script must receive the APK path as a single argument")
        assertEquals(2, args.size)
        val signedApkPath = File(args[1])
        assertTrue(signedApkPath.isAbsolute, "the script must receive an absolute path: ${args[1]}")
        assertTrue(args[1].endsWith(".tmp_aligned"), "the script must receive the aligned temporary APK: ${args[1]}")
        assertTrue(args[1].contains("out with space(测试)"), "unexpected APK path: ${args[1]}")

        val oldManifest = ApkReader(context.apkFile!!, logger).getManifest()
        val updatedManifest = ApkReader(copyApkFile, logger).getManifest()
        assertEquals(oldManifest.activities().size + 1, updatedManifest.activities().size)
    }

    @Test
    fun testCustomSignScriptFailureKeepsOriginalApk() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")
        val copyApkFile = File(tempCompileDir, "out/${context.apkFile!!.name}")
        copyApkFile.delete()
        context.apkFile!!.copyTo(copyApkFile)
        val originalContent = copyApkFile.readBytes()

        val runner = createScriptRunner("failing-sign.sh", """
            #!/bin/bash
            echo "sign server rejected the apk" >&2
            exit 7
        """.trimIndent())
        // The local signing config is valid, but a failed script must never fall back to it.
        val apkFileModifier = ApkFileModifier(copyApkFile, context.signingConfig, context.androidHome, logger, null, runner)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())

        assertFailsWith<IllegalStateException> {
            apkFileModifier.insertAndResign()
        }

        assertContentEquals(originalContent, copyApkFile.readBytes())
    }

    @Test
    fun testCustomSignScriptWithoutValidSignatureKeepsOriginalApk() {
        AndroidManifestCompilerTest().testAddActivity()
        val outputFile = File(stagingDir, "overlays/AndroidManifest.xml")
        val copyApkFile = File(tempCompileDir, "out/${context.apkFile!!.name}")
        copyApkFile.delete()
        context.apkFile!!.copyTo(copyApkFile)
        val originalContent = copyApkFile.readBytes()

        val runner = createScriptRunner("unsigned-sign.sh", """
            #!/bin/bash
            printf 'not an apk' > "${'$'}1"
            exit 0
        """.trimIndent())
        val apkFileModifier = ApkFileModifier(copyApkFile, context.signingConfig, context.androidHome, logger, null, runner)
        apkFileModifier.addFile("AndroidManifest.xml", outputFile.readBytes())

        assertFailsWith<IllegalStateException> {
            apkFileModifier.insertAndResign()
        }

        assertContentEquals(originalContent, copyApkFile.readBytes())
    }

    /**
     * Records the script arguments and delegates to the demo project sign script, so the test proves
     * both the argument contract and a real in-place signing round trip.
     */
    private fun createDemoSignScriptRunner(argsFile: File): CustomApkSignScriptRunner {
        val demoScript = File(assetsAndroidDir, "scripts/sign-system-apk.sh")
        assertTrue(demoScript.canExecute(), "demo sign script is missing or not executable: $demoScript")
        return createScriptRunner("record-sign-args.sh", """
            #!/bin/bash
            set -eu
            printf '%s\n' "${'$'}#" "${'$'}1" > '${argsFile.absolutePath}'
            exec '${demoScript.absolutePath}' "${'$'}@"
        """.trimIndent())
    }

    private fun createScriptRunner(name: String, content: String): CustomApkSignScriptRunner {
        val scriptFile = File(tempCompileDir, name)
        scriptFile.writeText(content + "\n")
        scriptFile.setExecutable(true)
        return CustomApkSignScriptRunner(
            "./$name",
            tempCompileDir,
            null,
            CompileUiHandler.DEFAULT,
            logger,
        )
    }
}
