package com.sickworm.intellij.jugg.compiler.source

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.ModuleApkBelongs
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.getDexMinApi
import com.sickworm.intellij.jugg.mock.SimpleCompileContext
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DexMinApiTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var logger: StdLogger
    private lateinit var dexFileMaker: DexFileMaker
    private lateinit var outputDir: File
    private lateinit var classesDir: File
    private lateinit var desugarConfiguration: String

    @Before
    fun setUp() {
        logger = TestGlobal.logger as StdLogger
        dexFileMaker = DexFileMaker(logger)
        outputDir = File(TestGlobal.buildDir, "dex_min_api_output")
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        classesDir = File(TestGlobal.buildDir, "dex_min_api_classes")
        classesDir.deleteRecursively()
        classesDir.mkdirs()
        desugarConfiguration = locateDesugarConfiguration().readText()
    }

    @Test
    fun `getDexMinApi uses application minSdk for library module in base apk`() {
        val context = createContext(appMinSdk = "29", libraryMinSdk = "21")
        val libraryModule = context.modules.getValue("library")

        assertEquals(29, context.getDexMinApi(libraryModule))
        assertEquals(29, context.getDexMinApi(context.applicationModule!!))
    }

    @Test
    fun `getDexMinApi ignores desugar flag and keeps high minSdk`() {
        val context = createContext(appMinSdk = "29", libraryMinSdk = "21")
        assertTrue(context.isEnableDesugared)
        assertEquals(29, context.getDexMinApi(context.applicationModule!!))
    }

    @Test
    fun `getDexMinApi falls back to 21 when minSdk is unavailable`() {
        val context = createContext(appMinSdk = null, libraryMinSdk = null)
        assertEquals(21, context.getDexMinApi(context.applicationModule!!))
    }

    @Test
    fun `d8 keeps java time descriptor when minApi is 29 with core library desugar config`() {
        val sourceFile = writeDateProviderSource()
        compileJava(sourceFile, classesDir)

        dexWithMinApi(29)

        val descriptors = currentDateMethodDescriptors()
        assertTrue(descriptors.any { it.contains("Ljava/time/LocalDate;") }, descriptors.toString())
        assertFalse(descriptors.any { it.contains("Lj$/time/LocalDate;") }, descriptors.toString())
    }

    @Test
    fun `d8 rewrites local date when minApi is 21 with core library desugar config`() {
        val sourceFile = writeDateProviderSource()
        compileJava(sourceFile, classesDir)

        dexWithMinApi(21)

        val descriptors = currentDateMethodDescriptors()
        assertTrue(descriptors.any { it.contains("Lj$/time/LocalDate;") }, descriptors.toString())
    }

    private fun createContext(appMinSdk: String?, libraryMinSdk: String?): SimpleCompileContext {
        val testRoot = temporaryFolder.root
        val appModule = module("app", ModuleInfo.Type.Application, appMinSdk, testRoot)
        val libraryModule = module("library", ModuleInfo.Type.Library, libraryMinSdk, testRoot)
        val modules = linkedMapOf(
            appModule.name to appModule,
            libraryModule.name to libraryModule,
        )
        val baseApk = ApkFileUnit("com.example.app", "", true, File(testRoot, "base.apk"))
        val belongsMap = ModuleApkBelongs(
            primaryApkMap = modules.values.associateWith { baseApk },
            allApkMap = modules.values.associateWith { listOf(baseApk) },
        )
        return SimpleCompileContext(
            logger = logger,
            tempCompileDir = File(testRoot, "compiled"),
            tempModuleDir = File(testRoot, "temp_module"),
            androidHome = TestGlobal.androidHome,
            androidJar = TestGlobal.androidJar,
            modules = modules,
            apkInfos = listOf(ApkInfo(listOf(baseApk), "com.example.app")),
            projectDir = testRoot,
            deployedFiles = mutableListOf(),
            incrementalDataDir = File(testRoot, "incremental"),
            customModuleBelongsApkMap = belongsMap,
        )
    }

    private fun module(
        name: String,
        type: ModuleInfo.Type,
        minSdkVersion: String?,
        testRoot: File,
    ): ModuleInfo {
        val moduleDir = File(testRoot, name)
        moduleDir.mkdirs()
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = type,
            minSdkVersion = minSdkVersion,
            moduleRootDir = moduleDir,
            projectRootDir = testRoot,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(testRoot, moduleDir, "debug", buildDirRelativePath = ""),
        )
    }

    private fun writeDateProviderSource(): File {
        val sourceDir = File(TestGlobal.buildDir, "dex_min_api_sources")
        sourceDir.mkdirs()
        return File(sourceDir, "DateProvider.java").apply {
            writeText(
                """
                package com.test;

                import java.time.LocalDate;

                public class DateProvider {
                    public LocalDate currentDate() {
                        return LocalDate.now();
                    }
                }
                """.trimIndent()
            )
        }
    }

    private fun dexWithMinApi(minApi: Int) {
        val dexOutputDir = File(outputDir, "min-api-$minApi")
        dexOutputDir.deleteRecursively()
        dexOutputDir.mkdirs()
        dexFileMaker.dex(
            outputDir = dexOutputDir,
            classFilesOrDir = classesDir.walkTopDown().filter { it.extension == "class" }.toList(),
            classpath = emptyList(),
            androidJar = TestGlobal.androidJar,
            minApi = minApi,
            isFilePerClass = false,
            desugaredLibraryConfiguration = desugarConfiguration,
            agpR8Classpath = null,
        )
        outputDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("min-api-") && it != dexOutputDir }
            ?.forEach { it.deleteRecursively() }
    }

    private fun currentDateMethodDescriptors(): List<String> {
        val dexOutputDir = outputDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("min-api-") }
            ?: error("dex output dir not found under $outputDir")
        val dexFiles = dexOutputDir.walkTopDown().filter { it.extension == "dex" }.toList()
        val classNode = parseDexFiles(dexFiles)["Lcom/test/DateProvider;"]
            ?: error("DateProvider not found in dex output")
        return classNode.methods
            .map { it.method }
            .filter { it.name == "currentDate" }
            .map { it.desc }
    }

    private fun locateDesugarConfiguration(): File {
        val candidates = listOf(
            File("idea/src/test/assets/assets/desugar.json"),
            File("../idea/src/test/assets/assets/desugar.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("desugar.json test fixture not found")
    }

    private fun compileJava(sourceFile: File, outputDir: File) {
        outputDir.mkdirs()
        val javacCmd = mutableListOf(
            "${TestGlobal.javaHome}/bin/javac",
            "-d", outputDir.absolutePath,
            "-source", "11",
            "-target", "11",
            "-cp", TestGlobal.androidJar.absolutePath,
            sourceFile.absolutePath,
        )
        val process = ProcessBuilder(javacCmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("javac failed with exit code $exitCode:\n$output")
        }
    }

    private fun parseDexFiles(dexFiles: List<File>): Map<String, DexClassNode> {
        val classes = mutableMapOf<String, DexClassNode>()
        dexFiles.forEach { dexFile ->
            val reader: BaseDexFileReader = MultiDexFileReader.open(dexFile.readBytes())
            val visitor = DexFileNode()
            reader.accept(visitor)
            visitor.clzs.forEach { classNode ->
                classes[classNode.className] = classNode
            }
        }
        return classes
    }
}
