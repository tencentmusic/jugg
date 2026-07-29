package com.sickworm.intellij.jugg.compile

import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompiler
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerOutputParser
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinCompileTest {

    private val kotlinCompiler = KotlinCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
    }

    private val resultTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Kotlin,
                File("$assetsKotlinDir/com/sickworm/intellij/jugg/test/Result.kt"),
                assetsKotlinDir,
                mockModule,
                dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
            )
        ),
        stagingDir
    )
    @Test
    fun kotlinCompile() {
        val task = resultTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result, "Companion")
    }

    private val activityTask = CompileTask(
        listOf(
            CompileFile(
            CompileFile.Type.Kotlin,
            File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
            File(assetsAndroidDir, "app/src/main/java/"),
            mockModule,
            dependencyPaths = listOf(androidJar.absolutePath)
                    + "$assetsAndroidDir/app/build/intermediates/javac/debug/classes"
                    + "$assetsAndroidDir/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar"
                    + LibraryParser().loadInTest()
        )
        ),
        stagingDir,
    )
    @Test
    fun kotlinProjectCompile() {
        val task = activityTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    @Test
    fun kotlinProjectCompileBenchmark() {
        val task = activityTask
        TimeLogger.start("kotlinCompile_cost1")
        kotlinCompiler.compile(task)
        val time1 = TimeLogger.end("kotlinCompile_cost1", logger)
        TimeLogger.start("kotlinCompile_cost2")
        repeat(20) {
            kotlinCompiler.compile(task)
        }
        val time2 = TimeLogger.end("kotlinCompile_cost2", logger)
        println("kotlinCompile_cost1 $time1 ms, kotlinCompile_cost2 $time2 ms")
    }

    @Test
    fun kotlinSmartCastCompile() {
        val task = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File(assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/ktsmartcast/ImplClass1.kt"),
                    File(assetsAndroidDir, "app/src/main/java/"),
                    mockModule,
                    dependencyPaths = LibraryParser().loadInTest(),
                )
            ),
            stagingDir,
        )
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    @Test
    fun kotlinInternalCompile() {
        val task = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File(assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/ktinternal/InvokeClass1.kt"),
                    File(assetsAndroidDir, "app/src/main/java/"),
                    mockModule,
                    dependencyPaths = LibraryParser().loadInTest(),
                )
            ),
            stagingDir,
        )
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    @Test
    fun kotlinCompileWithARouter() {
        // disable for now
//        val task = CompileTask(
//            listOf(
//                CompileFile(CompileFile.Type.Kotlin,
//                    File(assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/annotation/kotlin/KtARouterActivity.kt"),
//                    File(assetsAndroidDir, "app/src/main/java"),
//                    context.modules.first().value,
//                )
//            ),
//            stagingDir,
//        )
//        JuggSettings.isEnableApt = true
//        val result = kotlinCompiler.compile(task)
//        JuggSettings.isEnableApt = false
//
//        val mapper: OutputFileMapper = {
//            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "class")
//            listOf(CompileOutput(CompileOutput.Type.Class, outputFile, task.outputDir))
//        }
//        assertCompileResult(task, result, mapper)
//
//        listOf(
//            "ARouter\$\$Group\$\$app.class",
//            "ARouter\$\$Root\$\$app.class",
//            "ARouter\$\$Providers\$\$app.class",
//            "ARouter\$\$Group\$\$app.java",
//            "ARouter\$\$Root\$\$app.java",
//            "ARouter\$\$Providers\$\$app.java"
//        ).forEach { outputFileName ->
//            assertTrue(result.outputs.any { it.file.name == outputFileName }, "missing $outputFileName, " +
//                    "all are:\n${result.outputs.joinToString("\n") { it.file.name }}")
//        }
    }

    @Test
    fun kotlinAnnotationParcelizeCompile() {
        val task = createTask("com/sickworm/jugg/demo/testcase/annotation/kotlin/ParcelizeData.kt")
        val result = kotlinCompiler.compile(task)
        assertCompileResult(task, result, mapper)

        val task2 = createTask("com/sickworm/jugg/demo/testcase/annotation/kotlin/ParcelizeData2.kt")
        val result2 = kotlinCompiler.compile(task2)
        assertCompileResult(task2, result2, mapper)
    }

    @Test
    fun kotCompilerWithCompose() {
        test1()
        test1()
    }

    @Test
    fun kotlin23Agp9BuiltInFullDemoCompile() {
        try {
            GradleBuildHelper.switchKotlinVersion("2.3-agp9")
            AssembleAndroidProjectOnce.forceRecompile(true)
            val freshContext = context
            val libraryModule = freshContext.modules.getValue("library1")
            val appModule = freshContext.modules.getValue("app")
            assertEquals("debug", appModule.buildVariant)
            assertEquals("debug", libraryModule.buildVariant)
            assertTrue(appModule.moduleDependencies.any { it.moduleName == libraryModule.name })
            assertTrue(appModule.sourceDirs.any { it.canonicalFile == File(assetsAndroidDir, "app/src/main/java").canonicalFile })
            assertTrue(libraryModule.sourceDirs.any {
                it.canonicalFile == File(assetsAndroidDir, "library1/src/main/java").canonicalFile
            })
            assertEquals(
                "com.example.myapplication.MainActivity",
                ApkReader(freshContext.apkFile, logger).getDefaultActivity(),
            )
            assertEquals("build/app", appModule.buildPathInfo.buildDirRelativePath.replace('\\', '/'))
            assertEquals("build/library1", libraryModule.buildPathInfo.buildDirRelativePath.replace('\\', '/'))
            val libraryClass =
                "com/sickworm/jugg/demo/testcase/databinding/library1/DataBindingKotlinDemoActivityLibrary1.class"
            val builtInKotlinClassPath = File(
                libraryModule.buildPathInfo.buildDir,
                "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
            )
            val builtInKotlinClass = File(builtInKotlinClassPath, libraryClass)
            val legacyKotlinClass = File(
                libraryModule.buildPathInfo.buildDir,
                "tmp/kotlin-classes/debug/$libraryClass",
            )
            assertTrue(builtInKotlinClass.exists(), "missing AGP 9 Kotlin output: $builtInKotlinClass")
            assertFalse(legacyKotlinClass.exists(), "unexpected legacy Kotlin output: $legacyKotlinClass")
            assertEquals(
                builtInKotlinClassPath.canonicalPath,
                libraryModule.buildPathInfo.kotlinClassPath.canonicalPath,
            )
            val sourceRoot = File(assetsAndroidDir, "app/src/main/java")
            val source = File(sourceRoot, "com/example/myapplication/MainActivity.kt")
            val task = CompileTask(
                listOf(CompileFile(CompileFile.Type.Kotlin, source, sourceRoot, appModule)),
                stagingDir,
            )

            val result = KotlinCompiler(freshContext, mockParentDisposable).compile(task)

            assertTrue(result.isAllSuccess, result.toString())
            assertTrue(result.outputs.any { it.file.name == "MainActivity.class" })
        } finally {
            GradleBuildHelper.switchKotlinVersion("1.9")
            AssembleAndroidProjectOnce.forceRecompile(false)
        }
    }

    private fun test1() {
        val task = createTask("com/sickworm/jugg/demo/testcase/compose/MainComposeActivity.kt")
        val result = kotlinCompiler.compile(task)
        assertCompileResult(task, result, mapper)
    }

    @Test
    fun testMetadataError() {
        val message = "/Users/sickworm/MyApplication/build/jugg/classpath/root/MyApplication/app/build/tmp/kotlin-classes/" +
                "debug/META-INF/app_debug.kotlin_module: error: module was compiled with an incompatible version of Kotlin." +
                "The binary version of its metadata is 1.7.0, expected version is 1.1.16."
        assertTrue(KotlinCompilerOutputParser.MetadataVersionError.isMyError(message))
        val metadataVersionError = KotlinCompilerOutputParser.MetadataVersionError.create(message)
        assertNotNull(metadataVersionError)
        assertEquals(message, metadataVersionError.message)
        assertEquals(
            "/Users/sickworm/MyApplication/build/jugg/classpath/root/MyApplication/app/build/tmp/kotlin-classes/debug/META-INF/app_debug.kotlin_module",
            metadataVersionError.metadataFile.path)
        assertEquals("1.7.0", metadataVersionError.actualVersion)
        assertEquals("1.1.16", metadataVersionError.expectVersion)
    }

    @Test
    fun testKotlinCompilerExceptionMessage() {
        val parser = KotlinCompilerOutputParser(resultTask.files, logger)
        parser.printStream.println("exception: java.lang.IllegalArgumentException: 25.0.3")
        parser.printStream.println("\tat org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:298)")
        parser.flush()

        val result = parser.getResult(isCompileSuccess = false).first().getFailure()

        assertEquals("java.lang.IllegalArgumentException: 25.0.3", result.errors.first().second)
    }

    @Test
    fun testKspCompile() {
        val task = createTask("com/sickworm/jugg/demo/testcase/ksp/User.kt")
        val result = kotlinCompiler.compile(task)
        println("testKspCompile_output ${result.outputs.map { it.file.name }}")
        assertCompileResult(task, result, mapper)
        assertContentEquals(
            listOf("User.class", "UserProfile.class", "UserListResponse.class", "UserJsonAdapter.class", "UserListResponseJsonAdapter.class", "UserProfileJsonAdapter.class"),
            result.outputs.map { it.file.name }
        )
    }

    @Test
    fun testKsp1Compile() {
        // Save current version (should be KSP2/Kotlin 2.1)
        val originalVersion = "1.9"

        try {
            // Switch to KSP1 (Kotlin 1.9)
            println("Switching to Kotlin 1.9 (KSP1)...")
            GradleBuildHelper.switchKotlinVersion("1.9")

            // Force rebuild with KSP1
            println("Rebuilding project with KSP1...")
            AssembleAndroidProjectOnce.forceRecompile(true)

            // Run KSP1 test
            println("Testing KSP1 compilation...")
            val task = createTask("com/sickworm/jugg/demo/testcase/ksp/User.kt")
            val result = kotlinCompiler.compile(task)
            println("testKsp1Compile_output ${result.outputs.map { it.file.name }}")
            assertCompileResult(task, result, mapper)
            assertContentEquals(
                listOf("User.class", "UserProfile.class", "UserListResponse.class", "UserJsonAdapter.class", "UserListResponseJsonAdapter.class", "UserProfileJsonAdapter.class"),
                result.outputs.map { it.file.name }
            )

            println("KSP1 test passed!")
        } finally {
            // Always restore to original version (KSP2/Kotlin 2.1)
            println("Restoring to Kotlin $originalVersion (KSP2)...")
            GradleBuildHelper.switchKotlinVersion(originalVersion)

            // Rebuild with KSP2 to restore state
            println("Rebuilding project with KSP2...")
            AssembleAndroidProjectOnce.forceRecompile(true)

            println("Restored to original Kotlin version")
        }
    }

    private fun assertCompileResultKotlin(task: CompileTask, result: CompileResult, vararg subclassList: String) {
        val mapper: OutputFileMapper = { file ->
            (subclassList.toList() + "").map {
                val subName = if (it.isEmpty()) "" else "$$it"
                file.file.changeBaseDir(file.baseDir, task.outputDir, newName = "${file.file.nameWithoutExtension}$subName.class")
            }.map {
                CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
            }
        }
        assertCompileResult(task, result, mapper)
    }

    companion object {

        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, stagingDir, "class")
            listOf(CompileOutput(CompileOutput.Type.Class, outputFile, stagingDir))
        }

        private fun createTask(path: String): CompileTask {
            return CompileTask(listOf(ktCompileFile(path)), stagingDir)
        }

        private fun ktCompileFile(path: String): CompileFile {
            return CompileFile(CompileFile.Type.Kotlin,
                File(assetsAndroidDir, "app/src/main/java/$path"),
                File(assetsAndroidDir, "app/src/main/java"),
                context.modules.first().value,
            )
        }
    }
}
