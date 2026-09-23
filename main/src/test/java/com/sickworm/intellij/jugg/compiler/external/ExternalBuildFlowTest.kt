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
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputDir
import com.sickworm.intellij.jugg.project.info.ExternalBuildInputFilterRule
import com.sickworm.intellij.jugg.project.info.ExternalBuildType
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalBuildFlowTest {

    @Test
    fun `builds every native module sharing one external source`() {
        val root = Files.createTempDirectory("jugg-shared-native-source").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val sharedRoot = File(root, "shared").apply { mkdirs() }
            val sharedSource = File(sharedRoot, "shared.cpp").apply { writeText("void sharedCall() {}") }
            val appCommonOutput = File(root, "build/appcommon")
            val dtmpOutput = File(root, "build/dtmp")
            val appCommon = createCppModule(
                root,
                "appcommon",
                File(root, "mp/appcommon"),
                sharedRoot,
                ":mp:appcommon:mergeDebugNativeLibs",
                appCommonOutput,
            )
            val dtmp = createCppModule(
                root,
                "dtmp",
                File(root, "mp/dtmp"),
                sharedRoot,
                ":mp:dtmp:mergeDebugNativeLibs",
                dtmpOutput,
            )
            createCppCollectorGradleScript(root, listOf(
                CppStubTarget("appcommon", File(root, "mp/appcommon"), ":mp:appcommon:mergeDebugNativeLibs",
                    appCommonOutput, File(root, "stripped/appcommon"), "libappcommon.so"),
                CppStubTarget("dtmp", File(root, "mp/dtmp"), ":mp:dtmp:mergeDebugNativeLibs",
                    dtmpOutput, File(root, "stripped/dtmp"), "libdtmp.so"),
            ))
            val apk = File(root, "app.apk").also(::createEmptyApk)
            val context = SimpleCompileContext(
                logger = mock<Logger>(),
                tempCompileDir = File(root, "compiled"),
                tempModuleDir = File(root, "temp"),
                androidHome = File(root, "android-sdk"),
                androidJar = File(root, "android.jar"),
                modules = linkedMapOf(dtmp.name to dtmp, appCommon.name to appCommon),
                apkInfos = listOf(ApkInfo(apk, "com.example")),
                projectDir = root,
                deployedFiles = mutableListOf(),
                incrementalDataDir = File(root, "incremental"),
                fullBuildGradleCommand = "./gradlew :app:assembleDebug",
                externalBuildInfoInitScript = createInitScript(root),
            )

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, sharedSource, sharedRoot, dtmp)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(result.isAllSuccess)
            assertEquals(
                setOf("lib/arm64-v8a/libappcommon.so", "lib/arm64-v8a/libdtmp.so"),
                result.outputs.map { it.relativeFile.invariantSeparatorsPath }.toSet(),
            )
            val invocation = File(root, "invocation.txt").readText().split(Regex("\\s+")).filter(String::isNotEmpty)
            assertEquals(1, invocation.count { it == ":mp:appcommon:mergeDebugNativeLibs" })
            assertEquals(1, invocation.count { it == ":mp:dtmp:mergeDebugNativeLibs" })
            // The APK owner strip configuration is read, never executed: no app strip or app merge task
            // may enter the external invocation task graph.
            assertTrue(invocation.none { it.contains("stripDebugDebugSymbols") }, invocation.toString())
            assertTrue(invocation.none { it.contains(":app:merge") }, invocation.toString())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `deploys only the stripped native output of this invocation`() {
        val root = Files.createTempDirectory("jugg-stripped-native-output").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val cppRoot = File(root, "native").apply { mkdirs() }
            val cppOutput = File(root, "build/cpp")
            val module = createCppModule(
                root,
                "app",
                root,
                cppRoot,
                ":app:mergeDebugNativeLibs",
                cppOutput,
            )
            // The merge directory holds an unstripped library that must never be deployed.
            File(File(cppOutput, "arm64-v8a"), "libunstripped.so").apply {
                parentFile.mkdirs()
                writeText("unstripped")
            }
            createCppCollectorGradleScript(root, listOf(
                CppStubTarget("app", root, ":app:mergeDebugNativeLibs", cppOutput,
                    File(root, "stripped/app"), "libstripped.so"),
            ))
            val cppFile = File(cppRoot, "native.cpp").apply { writeText("void nativeCall() {}") }
            val context = createContext(root, module, "./gradlew :app:assembleDebug",
                externalBuildInfoInitScript = createInitScript(root))

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, cppFile, cppRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(result.isAllSuccess)
            val nativeOutputs = result.outputs.filter { it.type == CompileOutput.Type.NativeLib }
            assertEquals(
                listOf("lib/arm64-v8a/libstripped.so"),
                nativeOutputs.map { it.relativeFile.invariantSeparatorsPath },
            )
            assertEquals("stripped", nativeOutputs.single().file.readText())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `fails Cpp round when the invocation reports no stripped native output`() {
        val root = Files.createTempDirectory("jugg-cpp-no-stripped-output").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val cppRoot = File(root, "native").apply { mkdirs() }
            val cppOutput = File(root, "build/cpp")
            val module = createCppModule(root, "app", root, cppRoot, ":app:mergeDebugNativeLibs", cppOutput)
            File(File(cppOutput, "arm64-v8a"), "libnative.so").apply {
                parentFile.mkdirs()
                writeText("unstripped")
            }
            createCppCollectorGradleScript(root, listOf(
                CppStubTarget("app", root, ":app:mergeDebugNativeLibs", cppOutput, null, "libnative.so"),
            ))
            val cppFile = File(cppRoot, "native.cpp").apply { writeText("void nativeCall() {}") }
            val logger = mock<Logger>()
            val context = createContext(root, module, "./gradlew :app:assembleDebug",
                logger = logger, externalBuildInfoInitScript = createInitScript(root))

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, cppFile, cppRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty(), "unstripped module output must never be deployed")
            verify(logger).warn(argThat<String> { contains("Stripped native output is unavailable") })
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `fails shared source when any matching native build is unsupported`() {
        val root = Files.createTempDirectory("jugg-shared-native-unsupported").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val sharedRoot = File(root, "shared").apply { mkdirs() }
            val sharedSource = File(sharedRoot, "shared.cpp").apply { writeText("void sharedCall() {}") }
            val supported = createCppModule(
                root,
                "supported",
                File(root, "mp/supported"),
                sharedRoot,
                ":mp:supported:mergeDebugNativeLibs",
                File(root, "build/supported"),
            )
            val unsupported = createCppModule(
                root,
                "unsupported",
                File(root, "mp/unsupported"),
                sharedRoot,
                null,
                null,
                unsupportedReason = "Native task not found",
            )
            File(root, "gradlew").apply {
                writeText("""#!/bin/bash
                    touch "${File(root, "invoked.txt").path}"
                """.trimIndent())
                setExecutable(true)
            }
            val apk = File(root, "app.apk").also(::createEmptyApk)
            val context = SimpleCompileContext(
                logger = mock<Logger>(),
                tempCompileDir = File(root, "compiled"),
                tempModuleDir = File(root, "temp"),
                androidHome = File(root, "android-sdk"),
                androidJar = File(root, "android.jar"),
                modules = linkedMapOf(supported.name to supported, unsupported.name to unsupported),
                apkInfos = listOf(ApkInfo(apk, "com.example")),
                projectDir = root,
                deployedFiles = mutableListOf(),
                incrementalDataDir = File(root, "incremental"),
                fullBuildGradleCommand = "./gradlew :app:assembleDebug",
            )

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, sharedSource, sharedRoot, supported)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
            assertTrue(!File(root, "invoked.txt").exists())
            assertTrue(result.details.single().getFailure().errorMessages.contains("Native task not found"))
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

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
                externalBuildInfoInitScript = createInitScript(root),
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
            // Deploy planning identifies Flutter JIT runtime assets by the module owning the staged output.
            assertEquals(
                setOf(module.name),
                result.outputs.filter { it.type == CompileOutput.Type.Asset }
                    .map { it.relativeModule?.name }.toSet(),
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
            val logger = mock<Logger>()
            val context = createContext(
                root,
                module,
                fullBuildGradleCommand = "./gradlew :app:assembleDebug",
                logger = logger,
            )

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
            assertTrue(result.details.single().getFailure().errors.single().second
                .contains("Flutter native output is unavailable"))
            verify(logger).warn(argThat<String> { contains("Flutter native output is unavailable") })
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `accepts Cpp task with no deployable output`() {
        val root = Files.createTempDirectory("jugg-cpp-empty-output").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val cppRoot = File(root, "native").apply { mkdirs() }
            val cppOutput = File(root, "build/cpp")
            val module = createModule(
                root,
                File(root, "flutter"),
                cppRoot,
                File(root, "build/flutter"),
                File(root, "build/flutter-native.jar"),
                cppOutput,
            )
            createCppCollectorGradleScript(root, listOf(
                CppStubTarget("app", root, ":app:mergeDebugNativeLibs", cppOutput,
                    File(root, "stripped/app"), null),
            ))
            val cppFile = File(cppRoot, "native.cpp").apply { writeText("void nativeCall() {}") }
            val context = createContext(root, module, "./gradlew :app:assembleDebug",
                externalBuildInfoInitScript = createInitScript(root))

            val result = JuggCompiler(context, parent).compile(CompileTask(
                listOf(CompileFile(CompileFile.Type.ExternalBuildSource, cppFile, cppRoot, module)),
                File(root, "staging"),
                CompileStatusHolder.DEFAULT,
            ))

            assertTrue(result.isAllSuccess)
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
    fun `fails the whole round when one external input has no metadata`() {
        val root = Files.createTempDirectory("jugg-external-build-partial").toFile()
        val parent = object : Disposable {
            override fun dispose() = Unit
        }
        try {
            val flutterRoot = File(root, "flutter").apply { mkdirs() }
            val unresolvedRoot = File(root, "other-flutter").apply { mkdirs() }
            val module = createModule(
                root,
                flutterRoot,
                File(root, "native"),
                File(root, "build/flutter"),
                File(root, "build/flutter-native.jar"),
                File(root, "build/cpp"),
            )
            createGradleScript(root, File(root, "build/flutter"), File(root, "build/flutter-native.jar"), File(root, "build/cpp"))
            val dartFile = File(File(flutterRoot, "lib"), "main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val unresolvedDart = File(File(unresolvedRoot, "lib"), "other.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")

            val result = JuggCompiler(context, parent).compile(CompileTask(
                files = listOf(
                    CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module),
                    CompileFile(CompileFile.Type.ExternalBuildSource, unresolvedDart, unresolvedRoot, module),
                ),
                outputDir = File(root, "staging"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            ))

            assertTrue(!result.isAllSuccess)
            assertTrue(result.outputs.isEmpty())
            assertTrue(!File(root, "invocation.txt").exists(), "no external task may run for a partial round")
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `ignores removed Flutter native outputs on the second build`() {
        val root = Files.createTempDirectory("jugg-external-artifact-removed").toFile()
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
            val dartFile = File(File(flutterRoot, "lib"), "main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            createGradleScript(root, flutterOutput, flutterNativeDir)
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")
            val stagingDir = File(root, "staging")
            val task = {
                CompileTask(
                    listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                    stagingDir,
                    CompileStatusHolder.DEFAULT,
                )
            }

            val first = JuggCompiler(context, parent).compile(task())
            assertTrue(first.isAllSuccess)
            assertTrue(first.outputs.any { it.relativeFile.invariantSeparatorsPath == "lib/arm64-v8a/libapp.so" })
            context.deployedFiles += first.outputs

            // The next build keeps producing assets but drops the native library.
            createEmptyJniLibsGradleScript(root, flutterOutput, flutterNativeDir)
            val second = JuggCompiler(context, parent).compile(task())

            assertTrue(second.isAllSuccess)
            assertTrue(second.outputs.isEmpty())
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `unchanged Flutter assets are not treated as removed on the second build`() {
        val root = Files.createTempDirectory("jugg-flutter-unchanged-assets").toFile()
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
            val dartFile = File(File(flutterRoot, "lib"), "main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")
            val task = {
                CompileTask(
                    listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                    File(root, "staging"),
                    CompileStatusHolder.DEFAULT,
                )
            }

            createFlutterAssetsGradleScript(root, flutterOutput, flutterNativeDir, "first-kernel")
            val first = JuggCompiler(context, parent).compile(task())
            assertTrue(first.isAllSuccess)
            context.deployedFiles += first.outputs

            createFlutterAssetsGradleScript(root, flutterOutput, flutterNativeDir, "second-kernel")
            val second = JuggCompiler(context, parent).compile(task())

            assertTrue(second.isAllSuccess)
            assertEquals(
                listOf("assets/flutter_assets/kernel_blob.bin"),
                second.outputs.map { it.relativeFile.invariantSeparatorsPath },
            )
        } finally {
            Disposer.dispose(parent)
            root.deleteRecursively()
        }
    }

    @Test
    fun `ignores removed Flutter assets on the second build`() {
        val root = Files.createTempDirectory("jugg-flutter-removed-assets").toFile()
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
            val dartFile = File(File(flutterRoot, "lib"), "main.dart").apply {
                parentFile.mkdirs()
                writeText("void main() {}")
            }
            val context = createContext(root, module, fullBuildGradleCommand = "./gradlew :app:assembleDebug")
            val task = {
                CompileTask(
                    listOf(CompileFile(CompileFile.Type.ExternalBuildSource, dartFile, flutterRoot, module)),
                    File(root, "staging"),
                    CompileStatusHolder.DEFAULT,
                )
            }

            createFlutterAssetsGradleScript(
                root,
                flutterOutput,
                flutterNativeDir,
                "first-kernel",
                additionalAssetName = "removed.png",
            )
            val first = JuggCompiler(context, parent).compile(task())
            assertTrue(first.isAllSuccess)
            context.deployedFiles += first.outputs

            createFlutterAssetsGradleScript(root, flutterOutput, flutterNativeDir, "second-kernel")
            val second = JuggCompiler(context, parent).compile(task())

            assertTrue(second.isAllSuccess)
            assertEquals(
                listOf("assets/flutter_assets/kernel_blob.bin"),
                second.outputs.map { it.relativeFile.invariantSeparatorsPath },
            )
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
        logger: Logger = mock(),
        externalBuildInfoInitScript: File? = null,
    ): SimpleCompileContext {
        val apk = File(root, "app.apk").also(::createEmptyApk)
        return SimpleCompileContext(
            logger = logger,
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
            externalBuildInfoInitScript = externalBuildInfoInitScript,
        )
    }

    /** One C++ target of a stubbed external Gradle invocation. */
    private data class CppStubTarget(
        val moduleName: String,
        val moduleRoot: File,
        val taskPath: String,
        val mergeOutputDir: File,
        /** Stripped output directory of this invocation; null reports an invocation without strip output. */
        val strippedOutputDir: File?,
        /** Merge and stripped library name; null produces no native library at all. */
        val libName: String?,
    )

    private fun createInitScript(root: File): File {
        return File(root, "readProjectInfo.gradle.kts").apply { writeText("") }
    }

    /**
     * Writes a Gradle stub that produces the requested module merge outputs and one collector result
     * describing this invocation, including the stripped native output the compiler must consume.
     */
    private fun createCppCollectorGradleScript(root: File, targets: List<CppStubTarget>) {
        val updates = targets.joinToString(",") { target ->
            val strippedField = target.strippedOutputDir
                ?.let { ""","strippedNativeOutput":"${it.path}"""" }
                .orEmpty()
            """{"moduleName":"${target.moduleName}","moduleRootDir":"${target.moduleRoot.path}",""" +
                    """"buildVariant":"debug","previousTaskPath":"${target.taskPath}",""" +
                    """"externalBuildInfo":{"type":"Cpp","inputDirs":[{"directory":"${target.moduleRoot.path}","filterRules":["CppSource"]}],""" +
                    """"taskPath":"${target.taskPath}","nativeOutput":"${target.mergeOutputDir.path}",""" +
                    """"configFiles":[],"excludedDirs":[]}$strippedField}"""
        }
        val lines = mutableListOf(
            "#!/bin/bash",
            """echo "${'$'}@" > "${File(root, "invocation.txt").path}"""",
        )
        targets.forEach { target ->
            target.libName?.let { libName ->
                val mergeAbiDir = File(target.mergeOutputDir, "arm64-v8a")
                lines += """mkdir -p "${mergeAbiDir.path}""""
                lines += """printf unstripped > "${File(mergeAbiDir, libName).path}""""
            }
            target.strippedOutputDir?.let { strippedDir ->
                val strippedAbiDir = File(strippedDir, "arm64-v8a")
                lines += """mkdir -p "${strippedAbiDir.path}""""
                target.libName?.let { libName ->
                    lines += """printf stripped > "${File(strippedAbiDir, libName).path}""""
                }
            }
        }
        lines += collectResultScriptLines(updates)
        File(root, "gradlew").apply {
            writeText(lines.joinToString("\n") + "\n")
            setExecutable(true)
        }
    }

    /** Reads the collector arguments and writes one invocation result file. */
    private fun collectResultScriptLines(updates: String): List<String> = listOf(
        "for argument in \"${'$'}@\"; do",
        "    case \"${'$'}argument\" in",
        "        -Pjugg.externalBuildOutput=*) output=\"${'$'}{argument#*=}\" ;;",
        "        -Pjugg.externalBuildInvocation=*) invocation=\"${'$'}{argument#*=}\" ;;",
        "    esac",
        "done",
        "mkdir -p \"${'$'}output\"",
        "cat > \"${'$'}output/result.json\" <<JSON",
        """{"invocationId":"${'$'}invocation","updates":[$updates]}""",
        "JSON",
    )

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
                    listOf(ExternalBuildInputDir(flutterRoot, setOf(ExternalBuildInputFilterRule.Dart))),
                    flutterTaskPath,
                    flutterAssetsOutputDir,
                    flutterNativeOutput,
                ),
                ExternalBuildInfo(
                    ExternalBuildType.Cpp,
                    listOf(ExternalBuildInputDir(
                        cppRoot,
                        setOf(ExternalBuildInputFilterRule.CppSource, ExternalBuildInputFilterRule.CppHeader),
                    )),
                    ":app:mergeDebugNativeLibs",
                    null,
                    cppOutput,
                ),
            ),
        )
    }

    private fun createCppModule(
        projectRoot: File,
        name: String,
        moduleRoot: File,
        sourceRoot: File,
        taskPath: String?,
        output: File?,
        unsupportedReason: String? = null,
    ): ModuleInfo {
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = moduleRoot,
            projectRootDir = projectRoot,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectRoot, moduleRoot, "debug", buildDirRelativePath = "build"),
            externalBuildInfos = listOf(ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = listOf(ExternalBuildInputDir(
                    sourceRoot,
                    setOf(ExternalBuildInputFilterRule.CppSource, ExternalBuildInputFilterRule.CppHeader),
                )),
                taskPath = taskPath,
                assetsOutputDir = null,
                nativeOutput = output,
                unsupportedReason = unsupportedReason,
            )),
        )
    }

    private fun createGradleScript(root: File, flutterOutput: File, flutterArchive: File, cppOutput: File) {
        val strippedCppOutput = File(root, "build/cpp-stripped")
        val updates = """{"moduleName":"app","moduleRootDir":"${root.path}","buildVariant":"debug",""" +
                """"previousTaskPath":":flutter:packJniLibsflutterBuildDebug","externalBuildInfo":{""" +
                """"type":"Flutter","inputDirs":[{"directory":"${File(root, "flutter").path}","filterRules":["Dart"]}],""" +
                """"taskPath":":flutter:packJniLibsflutterBuildDebug",""" +
                """"assetsOutputDir":"${flutterOutput.path}","nativeOutput":"${flutterArchive.path}",""" +
                """"configFiles":[],"excludedDirs":[]}},""" +
                """{"moduleName":"app","moduleRootDir":"${root.path}","buildVariant":"debug",""" +
                """"previousTaskPath":":app:mergeDebugNativeLibs","externalBuildInfo":{"type":"Cpp",""" +
                """"inputDirs":[{"directory":"${root.path}","filterRules":["CppSource"]}],""" +
                """"taskPath":":app:mergeDebugNativeLibs",""" +
                """"nativeOutput":"${cppOutput.path}","configFiles":[],"excludedDirs":[]},""" +
                """"strippedNativeOutput":"${strippedCppOutput.path}"}"""
        val lines = mutableListOf(
            "#!/bin/bash",
            """echo "${'$'}@" > "${File(root, "invocation.txt").path}"""",
            """mkdir -p "${File(flutterOutput, "flutter_assets").path}"""",
            """mkdir -p "${File(root, "flutter-archive/lib/arm64-v8a").path}"""",
            """mkdir -p "${File(cppOutput, "arm64-v8a").path}"""",
            """mkdir -p "${File(strippedCppOutput, "arm64-v8a").path}"""",
            """printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"""",
            """printf flutter-so > "${File(root, "flutter-archive/lib/arm64-v8a/libapp.so").path}"""",
            """printf native-so > "${File(cppOutput, "arm64-v8a/libnative.so").path}"""",
            """printf stripped-native-so > "${File(strippedCppOutput, "arm64-v8a/libnative.so").path}"""",
            """cd "${File(root, "flutter-archive").path}"""",
            """jar cf "${flutterArchive.path}" lib""",
        )
        lines += collectResultScriptLines(updates)
        File(root, "gradlew").apply {
            writeText(lines.joinToString("\n") + "\n")
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
            // Flutter owns the jniLibs directory and regenerates it, so stale entries must disappear.
            writeText("""#!/bin/bash
                rm -rf "${flutterNativeDir.path}"
                mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${flutterNativeDir.path}"
                printf flutter-code > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
            """.trimIndent())
            setExecutable(true)
        }
    }

    private fun createFlutterAssetsGradleScript(
        root: File,
        flutterOutput: File,
        flutterNativeDir: File,
        kernelContent: String,
        additionalAssetName: String? = null,
    ) {
        val additionalAsset = additionalAssetName?.let { assetName ->
            """printf "additional-asset" > "${File(flutterOutput, "flutter_assets/$assetName").path}""""
        }.orEmpty()
        File(root, "gradlew").apply {
            writeText("""#!/bin/bash
                rm -rf "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${File(flutterOutput, "flutter_assets").path}"
                mkdir -p "${flutterNativeDir.path}"
                printf "$kernelContent" > "${File(flutterOutput, "flutter_assets/kernel_blob.bin").path}"
                printf "stable-manifest" > "${File(flutterOutput, "flutter_assets/AssetManifest.bin").path}"
                $additionalAsset
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
