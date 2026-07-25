package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.mock.androidApkPackage
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.data.ComposeResourceSupportStatus
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggCompilerTest {

    companion object {
        private val jugg = MockJugg()
    }

    @Before
    fun resetAllState() {
        jugg.dryFullCompile()
        assertTrue(jugg.deployStateManager.deployState.isReadyIncCompile)
    }

    /*******************************************************************
     * Source file test case:
     * language:    java / kotlin / java + kotlin
     * operation:   add / remove / update value / change signature
     * type:        static / non-static
     * object:      class / method / variable
     * count:       single / multiple
     *
     * other case:
     * * Part files compile failed
     * * Kotlin multiple class in one file
     * * Kotlin const value update (diffusion compilation)
     * * Kotlin inline method (diffusion compilation)
     *******************************************************************/

    // java class

    @Test
    fun testJavaClassAddSingle() {
        jugg.changeFileAndNotify("TestNewJavaFile.java" to "TestNewJavaFile.java")
        jugg.checkCompileResult("TestNewJavaFile.java", newClassesSize = 1)
    }

    @Test
    fun testJavaClassAddMultiple() {
        jugg.changeFileAndNotify(
            "TestNewJavaFile.java" to "TestNewJavaFile.java",
            "TestNewJavaFile2.java" to "TestNewJavaFile2.java")
        jugg.checkCompileResult("TestNewJavaFile.java", "TestNewJavaFile2.java", newClassesSize = 2)
    }

    @Test
    fun testJavaClassChangeSignature() {
        jugg.changeFileAndNotify("MainActivity2.changeSignature.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        jugg.dryDeploy()

        // second time deploy will be hot reload
        jugg.changeFileAndNotify("MainActivity2.changeSignature.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    // kotlin class

    @Test
    fun testKotlinClassAddSingle() {
        jugg.changeFileAndNotify("TestNewKotlinFile.kt" to "TestNewKotlinFile.kt")
        jugg.checkCompileResult("TestNewKotlinFile.kt", newClassesSize = 1)
    }

    @Test
    fun testKotlinClassAddMultiple() {
        jugg.changeFileAndNotify(
            "TestNewKotlinFile.kt" to "TestNewKotlinFile.kt",
            "TestNewKotlinFile2.kt" to "TestNewKotlinFile2.kt")
        jugg.checkCompileResult("TestNewKotlinFile.kt", "TestNewKotlinFile2.kt", newClassesSize = 2)
    }

    @Test
    fun testKotlinClassChangeSignature() {
        // there is an inner class inside MainActivity.kt
        // ↑ we disable desugar for now so no more inner class

        jugg.changeFileAndNotify("MainActivity.changeSignature.kt" to "MainActivity.kt")
        jugg.checkCompileResult("MainActivity.kt",
            newClassesSize = 0,
            hotReloadModifiedClassesSize = 1,
            hotFixModifiedClassesSize = 1)

        jugg.dryDeploy()

        // second time deploy will be hot reload
        jugg.changeFileAndNotify("MainActivity.changeSignature.kt" to "MainActivity.kt")
        jugg.checkCompileResult("MainActivity.kt", hotReloadModifiedClassesSize = 2)
    }

    // java method

    @Test
    fun testJavaMethodAddSingle() {
        jugg.changeFileAndNotify("MainActivity2.addMethod.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodRemoveSingle() {
        jugg.changeFileAndNotify("MainActivity2.removeMethod.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        jugg.dryDeploy()

        // second time deploy will be hot reload
        jugg.changeFileAndNotify("MainActivity2.removeMethod.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeReturn() {
        jugg.changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        jugg.dryDeploy()

        // second time deploy will be hot reload
        jugg.changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeArgument() {
        jugg.changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        jugg.dryDeploy()

        // second time deploy will be hot reload
        jugg.changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeReturnThenChangeArgument() {
        jugg.changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        jugg.dryDeploy()

        // second time deploy will be hot reload
        jugg.changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeContent() {
        jugg.changeFileAndNotify("MainActivity2.changeContent.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    // java static method, skip because I don't have static method on demo project, and
    // I need to implement auto-build on demo project apk first

    // kotlin method, skip because I don't want to write now

    // java variable

    @Test
    fun testJavaVariableAdd() {
        jugg.changeFileAndNotify("MainActivity2.java" to "MainActivity2.java")
        jugg.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testCompileResDir() {
        val file = ChangedFile(
            CompileFile.Type.Resource,
            File(assetsAndroidDir, "app/src/main/res"),
            File(assetsAndroidDir, "app/src/main/res"),
            jugg.compileContextManager.compileContext.tempModule,
        )
        jugg.deployFileManager.addChangedFile(listOf(file))
        jugg.juggManager.compileChanges()

        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
        var deployData = jugg.deployFileManager.getDeployData()
        assertTrue(deployData.isFullRes)
        jugg.dryDeploy()

        jugg.deployFileManager.addChangedFile(listOf(file))
        jugg.juggManager.compileChanges()
        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
        deployData = jugg.deployFileManager.getDeployData()
        println("deployData.overlays.size ${deployData.overlays.size}")
        assertFalse(deployData.isFullRes)
        assertTrue(deployData.overlays.size > 10) // 10 is just an approximate number
    }

    @Test
    fun testAssetDir() {
        val file = ChangedFile(
            CompileFile.Type.Asset,
            File(assetsAndroidDir, "app/src/main/assets"),
            File(assetsAndroidDir, "app/src/main/assets"),
            jugg.compileContextManager.compileContext.tempModule,
        )
        jugg.deployFileManager.addChangedFile(listOf(file))
        jugg.juggManager.compileChanges()

        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
        var deployData = jugg.deployFileManager.getDeployData()
        assertTrue(deployData.isFullRes)
        jugg.dryDeploy()

        jugg.deployFileManager.addChangedFile(listOf(file))
        jugg.juggManager.compileChanges()
        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
        deployData = jugg.deployFileManager.getDeployData()
        println("deployData.overlays.size ${deployData.overlays.size}")
        assertFalse(deployData.isFullRes)
        assertTrue(deployData.overlays.size > 1)
    }

    @Test
    fun testKotlinPageShouldRewriteKuiklyGeneratedEntry() {
        val route = "jugg_integration_page"
        val pageSourceFile = File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/JuggAptPage.kt")
        val generatedEntryFile = File(assetsAndroidDir, "app/build/generated/ksp/debug/kotlin/KuiklyCoreEntry.kt")

        val pageSource = """
            package com.example.myapplication

            // import com.tencent.kuikly.core.annotations.Page

            annotation class Page(val route: String)

            @Page("$route")
            class JuggAptPage
        """.trimIndent()
        val entrySource = """
            package com.example.myapplication.generated

            object BridgeManager {
                fun registerPageRouter(route: String, creator: () -> Any?) {
                }
            }

            object KuiklyCoreEntry {
                fun triggerRegisterPages() {
                }
            }
        """.trimIndent()

        withPatchedFiles(
            pageSourceFile to pageSource,
            generatedEntryFile to entrySource,
        ) {
            jugg.notifyFileChanges(listOf(pageSourceFile))
            jugg.compileChangedFiles()

            assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
            val dexFile = File(jugg.pathManager.stagingDir, "classes/${androidApkPackage.replace('.', '/')}/JuggAptPage.dex")
            assertTrue(dexFile.exists(), "Expected dex exists: ${dexFile.absolutePath}")

            val entryContent = generatedEntryFile.readText()
            assertTrue(entryContent.contains("""BridgeManager.registerPageRouter("$route")"""))
            assertTrue(entryContent.contains("com.example.myapplication.JuggAptPage()"))
        }
    }

    @Test
    fun testKotlinPageShouldCompileGeneratedEntryAfterUnrelatedFailureFixed() {
        val route = "jugg_retry_page"
        val sourceDir = File(assetsAndroidDir, "app/src/main/java/com/example/myapplication")
        val pageSourceFile = File(sourceDir, "JuggAptRetryPage.kt")
        val unrelatedSourceFile = File(sourceDir, "JuggAptUnrelated.kt")
        val generatedEntryFile = File(assetsAndroidDir, "app/build/generated/ksp/debug/kotlin/KuiklyCoreEntry.kt")
        val pageSource = """
            package com.example.myapplication

            // import com.tencent.kuikly.core.annotations.Page

            annotation class Page(val route: String)

            @Page("$route")
            class JuggAptRetryPage
        """.trimIndent()
        val brokenUnrelatedSource = """
            package com.example.myapplication

            class JuggAptUnrelated {
                fun value(): String = missingValue
            }
        """.trimIndent()
        val validUnrelatedSource = """
            package com.example.myapplication

            class JuggAptUnrelated {
                fun value(): String = "fixed"
            }
        """.trimIndent()
        val entrySource = """
            package com.example.myapplication.generated

            object BridgeManager {
                fun registerPageRouter(route: String, creator: () -> Any?) {
                }
            }

            object KuiklyCoreEntry {
                fun triggerRegisterPages() {
                }
            }
        """.trimIndent()

        withPatchedFiles(
            pageSourceFile to pageSource,
            unrelatedSourceFile to brokenUnrelatedSource,
            generatedEntryFile to entrySource,
        ) {
            jugg.notifyFileChanges(listOf(pageSourceFile, unrelatedSourceFile))
            jugg.compileChangedFiles()
            assertTrue(jugg.deployFileManager.getUncompiledFiles().isNotEmpty())

            unrelatedSourceFile.writeText(validUnrelatedSource)
            jugg.notifyFileChanges(listOf(unrelatedSourceFile))
            jugg.compileChangedFiles()

            assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
            val pageDexFile = File(
                jugg.pathManager.stagingDir,
                "classes/${androidApkPackage.replace('.', '/')}/JuggAptRetryPage.dex",
            )
            assertTrue(pageDexFile.exists(), "Expected page dex exists: ${pageDexFile.absolutePath}")
            val entryDexFile = File(
                jugg.pathManager.stagingDir,
                "classes/com/example/myapplication/generated/KuiklyCoreEntry.dex",
            )
            assertTrue(entryDexFile.exists(), "Expected generated entry dex exists: ${entryDexFile.absolutePath}")
        }
    }

    private fun withPatchedFiles(vararg patches: Pair<File, String>, block: () -> Unit) {
        val backup = patches.associate { (file, _) -> file to if (file.exists()) file.readText() else null }
        try {
            patches.forEach { (file, newContent) ->
                file.parentFile?.mkdirs()
                file.writeText(newContent)
            }
            block()
        } finally {
            backup.forEach { (file, oldContent) ->
                when (oldContent) {
                    null -> if (file.exists()) file.delete()
                    else -> file.writeText(oldContent)
                }
            }
        }
    }
}

/**
 * Reproduces KMP Compose resource generation and commonMain compiler argument failures.
 */
class KmpComposeFlowReproTest {

    companion object {
        private const val FIXTURE_COMPILE_COMMAND =
            "./gradlew :app:assembleDebug"
        private val pathManager = JuggPathManager(projectInfo.projectRoot)
        private val projectInfoFiles = listOf(pathManager.ideProjectInfoFile, pathManager.gradleProjectInfoFile)
        private val projectInfoBackups = mutableMapOf<File, ByteArray?>()

        @BeforeClass
        @JvmStatic
        fun prepareFixture() {
            AssembleAndroidProjectOnce.ensure()
            projectInfoFiles.forEach { file ->
                projectInfoBackups[file] = file.takeIf { it.exists() }?.readBytes()
            }

            try {
                GradleBuildHelper.switchKotlinVersion("2.1")
                assembleFixture()
                writeCommonMainIdeProjectInfo("2.1")
            } catch (throwable: Throwable) {
                restoreFixture()
                throw throwable
            }
        }

        @AfterClass
        @JvmStatic
        fun restoreFixture() {
            try {
                GradleBuildHelper.switchKotlinVersion("1.9")
                AssembleAndroidProjectOnce.forceRecompile(isNeedClean = false)
            } finally {
                projectInfoBackups.forEach { (file, content) ->
                    if (content == null) {
                        file.delete()
                    } else {
                        file.parentFile?.mkdirs()
                        file.writeBytes(content)
                    }
                }
            }
        }

        private fun assembleFixture(extraArgs: List<String> = emptyList()) {
            val initScript = File("../main/src/main/resources/gradle/readProjectInfo.gradle.kts").absoluteFile
            val command = listOf(
                "./gradlew",
                ":app:assembleDebug",
                "--no-daemon",
                "-I",
                initScript.absolutePath,
            ) + extraArgs
            val process = ProcessBuilder(command)
                .directory(projectInfo.projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "KMP Compose fixture assemble failed:\n$output"
            }
        }

        private fun writeCommonMainIdeProjectInfo(version: String) {
            val gradleProjectInfo = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger).load()
                ?: error("KMP Compose Gradle project info was not generated")
            val parentModule = gradleProjectInfo.modules["kmpCompose"]
                ?: error("kmpCompose module was not found in Gradle project info")
            val composeInfo = parentModule.composeResourceInfo ?: error("Compose resource info missing")
            assertEquals(ComposeResourceSupportStatus.Supported, composeInfo.supportStatus)
            assertEquals("com.sickworm.jugg.demo.kmp.generated.resources", composeInfo.packageName)
            assertTrue(composeInfo.generatorClasspath.any { it.name.startsWith("compose-gradle-plugin-") })
            if (version == "1.9") {
                assertTrue(composeInfo.usesLegacyGenerator)
                assertEquals(setOf("commonMain"), composeInfo.resourceDirectories.map { it.sourceSetName }.toSet())
                assertEquals("", composeInfo.assetRelativePath)
            } else {
                assertTrue(composeInfo.publicResClass)
                assertTrue(composeInfo.resourceDirectories.map { it.sourceSetName }.containsAll(setOf("commonMain", "androidMain")))
                assertTrue(composeInfo.resourceDirectories.any { it.directory.path.endsWith("src/androidMain/customComposeResources") })
                assertEquals(
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources",
                    composeInfo.assetRelativePath.replace('\\', '/'),
                )
            }
            writeIdeSourceSetProjectInfo(parentModule)
        }

        private fun writeIdeSourceSetProjectInfo(parentModule: ModuleInfo) {
            val commonMainModule = ModuleInfo.virtualModule.copy(
                name = "kmpCompose.commonMain",
                moduleType = ModuleInfo.Type.Unknown,
                moduleRootDir = parentModule.moduleRootDir,
                projectRootDir = parentModule.projectRootDir,
                sourceDirs = listOf(File(parentModule.moduleRootDir, "src/commonMain/kotlin")),
                buildVariant = parentModule.buildVariant,
                buildPathInfo = parentModule.buildPathInfo,
            )
            ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger).save(
                JuggProjectInfo(mapOf(commonMainModule.name to commonMainModule))
            )
        }
    }

    @Test
    fun compileComposeResourcesWithKotlin19() {
        try {
            GradleBuildHelper.switchKotlinVersion("1.9")
            assembleFixture()
            writeCommonMainIdeProjectInfo("1.9")
            val resourceFile = File(projectInfo.projectRoot, "kmpCompose/src/commonMain/composeResources/values/strings.xml")
            changeAndRevert(resourceFile, "Baseline title", "Legacy changed title") {
                val jugg = newFixtureJugg()
                jugg.dryFullCompile()
                jugg.notifyFileChanges(listOf(resourceFile))
                jugg.compileChangedFiles()

                assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
                assertEquals(setOf("values/strings.xml"), stagingAssetPaths(jugg))
                assertTrue(stagingDexPaths(jugg).any { it.endsWith("String0Kt.dex") })
            }
        } finally {
            GradleBuildHelper.switchKotlinVersion("2.1")
            assembleFixture()
            writeCommonMainIdeProjectInfo("2.1")
        }
    }

    @Test
    fun compileComposeResourcesWithKotlin23() {
        try {
            GradleBuildHelper.switchKotlinVersion("2.3")
            assembleFixture()
            writeCommonMainIdeProjectInfo("2.3")
            val resourceFile = File(projectInfo.projectRoot, "kmpCompose/src/commonMain/composeResourcesExtended/values/strings.xml")
            changeAndRevert(resourceFile, "Baseline title", "Latest changed title") {
                val jugg = newFixtureJugg()
                jugg.dryFullCompile()
                jugg.notifyFileChanges(listOf(resourceFile))
                jugg.compileChangedFiles()

                assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
                assertTrue(stagingDexPaths(jugg).any { it.contains("String0_commonMainKt") })
            }
        } finally {
            GradleBuildHelper.switchKotlinVersion("2.1")
            assembleFixture()
            writeCommonMainIdeProjectInfo("2.1")
        }
    }

    @Test
    fun compileNewComposeStringKeyAndSourceReference() {
        val resourceFile = File(
            projectInfo.projectRoot,
            "kmpCompose/src/commonMain/composeResourcesExtended/values/strings.xml",
        )
        val sourceFile = File(
            projectInfo.projectRoot,
            "kmpCompose/src/commonMain/kotlin/com/sickworm/jugg/demo/kmp/KmpComposeResourceCase.kt",
        )

        changeAndRevert(resourceFile, "</resources>", "    <string name=\"incremental_title\">Incremental title</string>\n</resources>") {
            changeAndRevert(sourceFile, "baseline_title", "incremental_title") {
                val jugg = MockJugg(
                    compileCommand = FIXTURE_COMPILE_COMMAND,
                    isIdeSynced = true,
                )
                jugg.dryFullCompile()
                jugg.notifyFileChanges(listOf(resourceFile, sourceFile))

                jugg.compileChangedFiles()

                assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
                assertTrue(stagingDexPaths(jugg).any { it.endsWith("KmpComposeResourceCase.dex") })
                assertTrue(stagingDexPaths(jugg).any { it.contains("String0_commonMainKt") })
                assertNoComposeGradle(jugg)
            }
        }
    }

    @Test
    fun compileChangedComposeStringValueToCvrAsset() {
        val resourceFile = File(
            projectInfo.projectRoot,
            "kmpCompose/src/commonMain/composeResourcesExtended/values/strings.xml",
        )

        changeAndRevert(resourceFile, "Baseline title", "Changed title") {
            val jugg = newFixtureJugg()
            jugg.dryFullCompile()
            jugg.notifyFileChanges(listOf(resourceFile))
            jugg.compileChangedFiles()

            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
            assertEquals(
                setOf("composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/strings.commonMain.cvr"),
                stagingAssetPaths(jugg),
            )
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileComposeArrayPluralDrawableFontAndFile() {
        val root = File(projectInfo.projectRoot, "kmpCompose/src/commonMain/composeResourcesExtended")
        val files = listOf(
            File(root, "values/arrays.xml") to "Alpha" to "Changed Alpha",
            File(root, "values/plurals.xml") to "%1\$d turn" to "%1\$d changed turn",
            File(root, "drawable/baseline_icon.png") to null to null,
            File(root, "font/baseline_font.ttf") to null to null,
            File(root, "files/baseline_payload.txt") to null to null,
        )
        withPatchedComposeFiles(files) { changedFiles ->
            val jugg = newFixtureJugg()
            jugg.dryFullCompile()
            jugg.notifyFileChanges(changedFiles)
            jugg.compileChangedFiles()

            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
            assertEquals(
                setOf(
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/arrays.commonMain.cvr",
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/plurals.commonMain.cvr",
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/drawable/baseline_icon.png",
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/font/baseline_font.ttf",
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/files/baseline_payload.txt",
                ),
                stagingAssetPaths(jugg),
            )
            assertTrue(stagingDexPaths(jugg).any { it.contains("Array0_commonMainKt") })
            assertTrue(stagingDexPaths(jugg).any { it.contains("Plurals0_commonMainKt") })
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileComposeQualifiersAndCustomDirectory() {
        val commonFile = File(projectInfo.projectRoot, "kmpCompose/src/commonMain/composeResourcesExtended/values-zh-rCN/strings.xml")
        val customFile = File(projectInfo.projectRoot, "kmpCompose/src/androidMain/customComposeResources/values/android_strings.xml")
        val androidResource = File(projectInfo.projectRoot, "app/src/main/res/values/strings.xml")
        withPatchedComposeFiles(
            listOf(
                commonFile to "基线标题" to "变更标题",
                customFile to "Android baseline title" to "Android changed title",
                androidResource to "My Application" to "My Changed Application",
            ),
        ) { changedFiles ->
            val jugg = newFixtureJugg()
            jugg.dryFullCompile()
            jugg.notifyFileChanges(changedFiles)
            jugg.compileChangedFiles()

            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
            assertTrue(stagingAssetPaths(jugg).containsAll(
                setOf(
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values-zh-rCN/strings.commonMain.cvr",
                    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/android_strings.androidMain.cvr",
                ),
            ))
            assertTrue(jugg.deployFileManager.getStagingFiles().any { it.type == CompileOutput.Type.Res })
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun rejectInvalidComposeValuesXmlWithoutRunningGradle() {
        val resourceFile = File(projectInfo.projectRoot, "kmpCompose/src/commonMain/composeResourcesExtended/values/strings.xml")
        changeAndRevert(resourceFile, "</resources>", "<string name=\"broken\"></resources>") {
            val jugg = newFixtureJugg()
            jugg.dryFullCompile()
            jugg.notifyFileChanges(listOf(resourceFile))
            jugg.compileChangedFiles()

            assertEquals(listOf(resourceFile.canonicalFile), jugg.deployFileManager.getUncompiledFiles().map { it.file.canonicalFile })
            assertTrue(jugg.readLatestProjectLog().contains(resourceFile.absolutePath))
            assertNoComposeGradle(jugg)
        }
    }

    private fun newFixtureJugg() = MockJugg(
        compileCommand = FIXTURE_COMPILE_COMMAND,
        isIdeSynced = true,
    )

    private fun stagingDexPaths(jugg: MockJugg): Set<String> = jugg.deployFileManager.getStagingFiles()
        .filter { it.type == CompileOutput.Type.Dex }
        .map { it.relativeFile.path.replace('\\', '/') }
        .toSet()

    private fun stagingAssetPaths(jugg: MockJugg): Set<String> = jugg.deployFileManager.getStagingFiles()
        .filter { it.type == CompileOutput.Type.Asset }
        .map { it.relativeFile.path.replace('\\', '/').substringAfter("assets/") }
        .toSet()

    private fun assertNoComposeGradle(jugg: MockJugg) {
        val log = jugg.readLatestProjectLog()
        assertFalse(log.contains("./gradlew"), log)
        assertFalse(log.contains("generateComposeResClass"), log)
        assertFalse(log.contains("generateResourceAccessors"), log)
        assertFalse(log.contains("prepareComposeResources"), log)
    }

    private fun withPatchedComposeFiles(
        patches: List<Pair<Pair<File, String?>, String?>>,
        block: (List<File>) -> Unit,
    ) {
        val backups = patches.associate { (fileAndOld, _) -> fileAndOld.first to fileAndOld.first.readBytes() }
        try {
            patches.forEach { (fileAndOld, newContent) ->
                val (file, oldContent) = fileAndOld
                if (oldContent == null) file.appendBytes(byteArrayOf(0))
                else file.writeText(file.readText().replace(oldContent, newContent!!))
            }
            block(patches.map { it.first.first })
        } finally {
            backups.forEach { (file, bytes) -> file.writeBytes(bytes) }
        }
    }
}
