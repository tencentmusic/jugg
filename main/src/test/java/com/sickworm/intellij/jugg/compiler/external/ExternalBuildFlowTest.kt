package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.JuggCompiler
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalBuildFlowTest {

    @Test
    fun `routes Flutter assets and native outputs through Jugg compile flow`() {
        val root = Files.createTempDirectory("jugg-external-build-flow").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val cppRoot = File(root, "native").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            val cppOutput = File(root, "build/cpp")
            val module = createModule(root, flutterRoot, cppRoot, flutterOutput, cppOutput)
            val apk = File(root, "app.apk").also(::createEmptyApk)
            val context = SimpleCompileContext(
                logger = mock<Logger>(),
                tempCompileDir = File(root, "compiled"),
                tempModuleDir = File(root, "temp"),
                androidHome = File(root, "android-sdk"),
                androidJar = File(root, "android.jar"),
                modules = mapOf(module.name to module),
                apkInfos = listOf(ApkInfo(apk, "com.example")),
                projectDir = root,
                deployedFiles = mutableListOf(),
                incrementalDataDir = File(root, "incremental"),
            )
            createGradleScript(root, flutterOutput, cppOutput)
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val cppFile = File(cppRoot, "native.cpp").apply { writeText("void nativeCall() {}") }
            val result = JuggCompiler(context, parent).compile(CompileTask(
                files = listOf(
                    CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module),
                    CompileFile(CompileFile.Type.ExternalBuildSource, cppFile, cppRoot, module),
                ),
                outputDir = File(root, "staging"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
                gradleCommand = "./gradlew :app:assembleDebug --offline",
            ))

            assertTrue(result.isAllSuccess)
            assertEquals(
                setOf(
                    "assets/flutter_assets/kernel_blob.bin",
                    "lib/arm64-v8a/libapp.so",
                    "lib/arm64-v8a/libnative.so",
                ),
                result.outputs.filter {
                    it.type == CompileOutput.Type.Asset || it.type == CompileOutput.Type.NativeLib
                }.map { it.relativeFile.invariantSeparatorsPath }.toSet(),
            )
            val invocation = File(root, "invocation.txt").readText()
            assertTrue(invocation.contains(":flutter:compileFlutterBuildDebug"))
            assertTrue(invocation.contains(":app:mergeDebugNativeLibs"))
            assertTrue(!invocation.contains(":app:assembleDebug"))
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not deploy old outputs when external build fails`() {
        val root = Files.createTempDirectory("jugg-external-build-failure").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            File(flutterOutput, "flutter_assets/kernel_blob.bin").apply {
                parentFile.mkdirs()
                writeText("old-output")
            }
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                flutterOutput,
                File(root, "build/cpp"),
            )
            val apk = File(root, "app.apk").also(::createEmptyApk)
            val context = SimpleCompileContext(
                logger = mock<Logger>(),
                tempCompileDir = File(root, "compiled"),
                tempModuleDir = File(root, "temp"),
                androidHome = File(root, "android-sdk"),
                androidJar = File(root, "android.jar"),
                modules = mapOf(module.name to module),
                apkInfos = listOf(ApkInfo(apk, "com.example")),
                projectDir = root,
                deployedFiles = mutableListOf(),
                incrementalDataDir = File(root, "incremental"),
            )
            File(root, "gradlew").apply {
                writeText("#!/bin/bash\nexit 1\n")
                setExecutable(true)
            }
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }

            val result = JuggCompiler(context, parent).compile(CompileTask(
                files = listOf(CompileFile(
                    CompileFile.Type.ExternalBuildSource,
                    dartFile,
                    flutterRoot,
                    module,
                )),
                outputDir = File(root, "staging"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
                gradleCommand = "./gradlew :app:assembleDebug",
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    private fun createModule(
        root: File,
        flutterRoot: File,
        cppRoot: File,
        flutterOutput: File,
        cppOutput: File,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            moduleRootDir = root,
            projectRootDir = root,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(root, root, "debug", buildDirRelativePath = "build"),
            externalBuildInfos = listOf(
                ExternalBuildInfo(
                    ExternalBuildType.Flutter,
                    listOf(flutterRoot),
                    ":flutter:compileFlutterBuildDebug",
                    flutterOutput,
                ),
                ExternalBuildInfo(
                    ExternalBuildType.Cpp,
                    listOf(cppRoot),
                    ":app:mergeDebugNativeLibs",
                    cppOutput,
                ),
            ),
        )
    }

    private fun createGradleScript(root: File, flutterOutput: File, cppOutput: File) {
        File(root, "gradlew").apply {
            writeText("""#!/bin/bash
                echo "${'$'}@" > "${File(root, "invocation.txt").path}"
                mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${File(flutterOutput, "arm64-v8a").path}"
                mkdir -p "${File(cppOutput, "arm64-v8a").path}"
                printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
                printf flutter-so > "${File(flutterOutput, "arm64-v8a/app.so").path}"
                printf native-so > "${File(cppOutput, "arm64-v8a/libnative.so").path}"
            """.trimIndent())
            setExecutable(true)
        }
    }

    private fun createEmptyApk(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
    }
}
