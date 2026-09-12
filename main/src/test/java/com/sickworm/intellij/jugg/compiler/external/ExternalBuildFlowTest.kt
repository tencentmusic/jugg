package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
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
            val flutterArchive = File(root, "build/flutter-native.jar")
            val cppOutput = File(root, "build/cpp")
            val module = createModule(root, flutterRoot, cppRoot, flutterOutput, flutterArchive, cppOutput)
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
                fullBuildGradleCommand = "./gradlew :app:assembleDebug --offline",
            )
            createGradleScript(root, flutterOutput, flutterArchive, cppOutput)
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val cppFile = File(cppRoot, "native.cpp").apply { writeText("void nativeCall() {}") }
            val staleModule = module.copy(externalBuildInfos = emptyList())
            val result = JuggCompiler(context, parent).compile(CompileTask(
                files = listOf(
                    CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, staleModule),
                    CompileFile(CompileFile.Type.ExternalBuildSource, cppFile, cppRoot, staleModule),
                ),
                outputDir = File(root, "staging"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
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
            assertTrue(invocation.contains(":flutter:packJniLibsflutterBuildDebug"))
            assertTrue(invocation.contains(":app:mergeDebugNativeLibs"))
            assertTrue(!invocation.contains(":app:assembleDebug"))
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `stages Flutter jniLibs directory outputs through Jugg compile flow`() {
        val root = Files.createTempDirectory("jugg-flutter-native-dir").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            val flutterNativeDir = File(root, "build/jniLibs")
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                flutterOutput,
                flutterNativeDir,
                File(root, "build/cpp"),
                flutterTaskPath = ":flutter:copyJniLibsflutterBuildDebug",
            )
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            createGradleScript(root, flutterOutput, flutterNativeDir)
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(result.isAllSuccess)
            assertEquals(
                setOf("assets/flutter_assets/kernel_blob.bin", "lib/arm64-v8a/libapp.so"),
                result.outputs.filter {
                    it.type == CompileOutput.Type.Asset || it.type == CompileOutput.Type.NativeLib
                }.map { it.relativeFile.invariantSeparatorsPath }.toSet(),
            )
            val invocation = File(root, "invocation.txt").readText()
            assertTrue(invocation.contains(":flutter:copyJniLibsflutterBuildDebug"))
            assertTrue(!invocation.contains(":app:assembleDebug"))
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `accepts empty Flutter jniLibs directory when debug assets exist`() {
        val root = Files.createTempDirectory("jugg-flutter-native-dir-empty").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            val flutterNativeDir = File(root, "build/jniLibs")
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                flutterOutput,
                flutterNativeDir,
                File(root, "build/cpp"),
                flutterTaskPath = ":flutter:copyJniLibsflutterBuildDebug",
            )
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            createEmptyJniLibsGradleScript(root, flutterOutput, flutterNativeDir)
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(result.isAllSuccess)
            assertEquals(
                listOf("assets/flutter_assets/kernel_blob.bin"),
                result.outputs.map { it.relativeFile.invariantSeparatorsPath },
            )
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `fails when Flutter jniLibs directory is not produced`() {
        val root = Files.createTempDirectory("jugg-flutter-native-dir-missing").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            val flutterNativeDir = File(root, "build/jniLibs")
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                flutterOutput,
                flutterNativeDir,
                File(root, "build/cpp"),
                flutterTaskPath = ":flutter:copyJniLibsflutterBuildDebug",
            )
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            File(root, "gradlew").apply {
                writeText("""#!/bin/bash
                    mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                    printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
                """.trimIndent())
                setExecutable(true)
            }
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
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
                File(root, "build/flutter-native.jar"),
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
                fullBuildGradleCommand = "./gradlew :app:assembleDebug",
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
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects unsafe Flutter native archive entries`() {
        val root = Files.createTempDirectory("jugg-flutter-archive-unsafe").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            val flutterArchive = File(root, "build/flutter-native.jar")
            File(flutterOutput, "flutter_assets/kernel_blob.bin").apply {
                parentFile.mkdirs()
                writeText("asset")
            }
            createArchive(flutterArchive, mapOf("lib/arm64-v8a/../evil.so" to "unsafe"))
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                flutterOutput,
                flutterArchive,
                File(root, "build/cpp"),
            )
            File(root, "gradlew").apply {
                writeText("#!/bin/bash\nexit 0\n")
                setExecutable(true)
            }
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val context = createContext(
                root,
                module,
                fullBuildGradleCommand = ":app:assembleDebug",
                scene = ICompileContext.Scene.INCREMENTAL_APK,
            )

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `accepts manifest only Flutter archive when assets exist`() {
        val root = Files.createTempDirectory("jugg-flutter-archive-assets").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val flutterOutput = File(root, "build/flutter")
            val flutterArchive = File(root, "build/flutter-native.jar")
            File(flutterOutput, "flutter_assets/kernel_blob.bin").apply {
                parentFile.mkdirs()
                writeText("asset")
            }
            createArchive(flutterArchive, mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n"))
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                flutterOutput,
                flutterArchive,
                File(root, "build/cpp"),
            )
            File(root, "gradlew").apply {
                writeText("#!/bin/bash\nexit 0\n")
                setExecutable(true)
            }
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(result.isAllSuccess)
            assertEquals(listOf("assets/flutter_assets/kernel_blob.bin"), result.outputs.map {
                it.relativeFile.invariantSeparatorsPath
            })
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `fails only external build when legacy context lacks Gradle command getter`() {
        val root = Files.createTempDirectory("jugg-external-build-legacy-context").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                File(root, "build/flutter"),
                File(root, "build/flutter-native.jar"),
                File(root, "build/cpp"),
            )
            val currentContext = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")
            val legacyContext = object : ICompileContext by currentContext {
                override val fullBuildGradleCommand: String?
                    get() = throw AbstractMethodError()
            }
            val dartFile = File(flutterRoot, "lib/main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }

            val result = JuggCompiler(legacyContext, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    private fun createContext(
        root: File,
        module: ModuleInfo,
        fullBuildGradleCommand: String,
        scene: ICompileContext.Scene = ICompileContext.Scene.IDE,
    ): SimpleCompileContext {
        val apk = File(root, "app.apk").also(::createEmptyApk)
        return SimpleCompileContext(
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
            fullBuildGradleCommand = fullBuildGradleCommand,
            scene = scene,
        )
    }

    private fun createModule(
        root: File,
        flutterRoot: File,
        cppRoot: File,
        flutterAssetsOutputDir: File,
        flutterNativeOutput: File?,
        cppOutput: File,
        flutterTaskPath: String = ":flutter:packJniLibsflutterBuildDebug",
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
                    flutterTaskPath,
                    flutterAssetsOutputDir,
                    flutterNativeOutput,
                ),
                ExternalBuildInfo(
                    ExternalBuildType.Cpp,
                    listOf(cppRoot),
                    ":app:mergeDebugNativeLibs",
                    null,
                    cppOutput,
                ),
            ),
        )
    }

    private fun createGradleScript(root: File, flutterOutput: File, flutterArchive: File, cppOutput: File) {
        File(root, "gradlew").apply {
            writeText("""#!/bin/bash
                echo "${'$'}@" > "${File(root, "invocation.txt").path}"
                mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${File(root, "flutter-archive/lib/arm64-v8a").path}"
                mkdir -p "${File(cppOutput, "arm64-v8a").path}"
                printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
                printf flutter-so > "${File(root, "flutter-archive/lib/arm64-v8a/libapp.so").path}"
                cd "${File(root, "flutter-archive").path}"
                jar cf "${flutterArchive.path}" lib
                printf native-so > "${File(cppOutput, "arm64-v8a/libnative.so").path}"
            """.trimIndent())
            setExecutable(true)
        }
    }

    private fun createGradleScript(root: File, flutterOutput: File, flutterNativeDir: File) {
        File(root, "gradlew").apply {
            writeText("""#!/bin/bash
                echo "${'$'}@" > "${File(root, "invocation.txt").path}"
                mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${File(flutterNativeDir, "arm64-v8a").path}"
                printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
                printf flutter-so > "${File(flutterNativeDir, "arm64-v8a/libapp.so").path}"
            """.trimIndent())
            setExecutable(true)
        }
    }

    private fun createEmptyJniLibsGradleScript(root: File, flutterOutput: File, flutterNativeDir: File) {
        File(root, "gradlew").apply {
            writeText("""#!/bin/bash
                mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${flutterNativeDir.path}"
                printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
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

    private fun createArchive(file: File, entries: Map<String, String>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
