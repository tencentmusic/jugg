package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.desugarDefaultInterfaceSuffix
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

    @Test
    fun testInheritedDefaultInterfaceOverrideIsKept() {
        val packageName = "com.sickworm.jugg.demo.testcase.defaultinterface"
        jugg.changeFileAndNotify(
            "ParentOverrideChildClass.addMarker.java" to "ParentOverrideChildClass.java",
            directory = "app/src/main/java/${packageName.replace('.', '/')}",
        )

        val dexFile = File(
            jugg.pathManager.stagingDir,
            "classes/${packageName.replace('.', '/')}/ParentOverrideChildClass.dex",
        )
        assertTrue(dexFile.isFile)
        val parsedDex = ApkParser().parseDexFiles(listOf(dexFile))
        assertFalse(parsedDex.methodRefs.any { it.key.owner.endsWith(desugarDefaultInterfaceSuffix) })
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
            jugg.compileContextManager.compileContext.applicationModule!!,
        )
        jugg.deployFileManager.addChangedFile(listOf(file))
        jugg.juggManager.compileChanges()

        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
        var deployData = jugg.deployFileManager.getDeployData()
        assertTrue(deployData.isFullRes)
        jugg.dryDeploy()

        file.file.setLastModified(file.file.lastModified() + 1_000)
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
            jugg.compileContextManager.compileContext.applicationModule!!,
        )
        jugg.deployFileManager.addChangedFile(listOf(file))
        jugg.juggManager.compileChanges()

        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size)
        var deployData = jugg.deployFileManager.getDeployData()
        assertTrue(deployData.isFullRes)
        jugg.dryDeploy()

        file.file.setLastModified(file.file.lastModified() + 1_000)
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
        val generatedEntryFile = File(
            jugg.compileContextManager.compileContext.applicationModule!!.buildPathInfo.generatedKspSourcePath,
            "KuiklyCoreEntry.kt",
        )

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
        val generatedEntryFile = File(
            jugg.compileContextManager.compileContext.applicationModule!!.buildPathInfo.generatedKspSourcePath,
            "KuiklyCoreEntry.kt",
        )
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
            val ownerModule = parentModule.copy(
                moduleType = ModuleInfo.Type.Unknown,
                libraryDependencies = emptyList(),
                runtimeLibraryDependencies = emptyList(),
            )
            val commonMainModule = ModuleInfo.virtualModule.copy(
                name = "kmpCompose.commonMain",
                moduleType = ModuleInfo.Type.Unknown,
                moduleRootDir = parentModule.moduleRootDir,
                projectRootDir = parentModule.projectRootDir,
                sourceDirs = listOf(
                    File(parentModule.moduleRootDir, "src/commonMain/kotlin"),
                    File(parentModule.moduleRootDir, "src/sharedMain/kotlin"),
                ),
                buildVariant = parentModule.buildVariant,
                buildPathInfo = parentModule.buildPathInfo,
            )
            ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger).save(
                JuggProjectInfo(
                    modules = mapOf(
                        ownerModule.name to ownerModule,
                        commonMainModule.name to commonMainModule,
                    ),
                    agpR8Classpath = null,
                )
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
                jugg.notifyFileChanges(listOf(resourceFile) + gradleComposeGeneratedSources())
                jugg.compileChangedFiles()

                assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty())
                assertEquals(setOf("values/strings.xml"), stagingRawAssetPaths(jugg))
                assertTrue(stagingDexPaths(jugg).any { it.endsWith("String0Kt.dex") })
                assertTrue(jugg.deployFileManager.getDeployData().overlays.any { it.name == "values/strings.xml" })
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
                assertTrue(gradleComposeGeneratedSources().any { it.readText().contains("incremental_title") })
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
            assertEquals(
                setOf("assets/composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/strings.commonMain.cvr"),
                stagingRawAssetPaths(jugg),
            )
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileComposeResourceWhenGradleGeneratedAccessorsAreAlsoReported() {
        val resourceFile = File(
            projectInfo.projectRoot,
            "kmpCompose/src/commonMain/composeResourcesExtended/values/strings.xml",
        )
        val generatedSources = gradleComposeGeneratedSources()
        assertTrue(generatedSources.isNotEmpty())

        changeAndRevert(resourceFile, "Baseline title", "Changed with generated accessors") {
            val jugg = newFixtureJugg()
            jugg.dryFullCompile()
            jugg.notifyFileChanges(listOf(resourceFile) + generatedSources)
            jugg.compileChangedFiles()

            val log = jugg.readLatestProjectLog()
            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), log)
            assertFalse(log.contains("defined multiple times"), log)
            assertTrue(stagingDexPaths(jugg).any { it.contains("String0") }, log)
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

    @Test
    fun compileChangedCommonExpectWithAndroidActual() {
        val common = kmpSource("commonMain", "PlatformLabel.kt")
        val actual = kmpSource("androidMain", "PlatformLabel.android.kt")

        changeAndRevert(common, ":baseline", ":common changed") {
            val jugg = compileKmpChanges(common)

            assertKmpCompileSuccess(jugg, common, actual)
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/PlatformLabelKt.dex") })
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/PlatformLabel.dex") })
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileChangedAndroidActualWithCommonExpect() {
        val common = kmpSource("commonMain", "PlatformLabel.kt")
        val actual = kmpSource("androidMain", "PlatformLabel.android.kt")

        changeAndRevert(actual, "\"Android\"", "\"Android changed\"") {
            val jugg = compileKmpChanges(actual)

            assertKmpCompileSuccess(jugg, common, actual)
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/PlatformLabel.dex") })
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileChangedExpectAndActualTogether() {
        val common = kmpSource("commonMain", "PlatformLabel.kt")
        val actual = kmpSource("androidMain", "PlatformLabel.android.kt")

        withPatchedKotlinFiles(
            common to common.readText().replace(":baseline", ":both changed"),
            actual to actual.readText().replace("\"Android\"", "\"Android both changed\""),
        ) {
            val jugg = compileKmpChanges(common, actual)

            assertKmpCompileSuccess(jugg, common, actual)
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileNewExpectActualApiCalledByApp() {
        val common = kmpSource("commonMain", "IncrementalPlatformApi.kt")
        val actual = kmpSource("androidMain", "DifferentAndroidIncrementalApi.kt")
        val app = File(projectInfo.projectRoot, "app/src/main/java/com/example/myapplication/MainActivity.kt")
        val commonSource = """
            package com.sickworm.jugg.demo.kmp

            expect fun incrementalPlatformValue(): String
        """.trimIndent()
        val actualSource = """
            package com.sickworm.jugg.demo.kmp

            actual fun incrementalPlatformValue(): String = "incremental"
        """.trimIndent()
        val appSource = app.readText().replace(
            "Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)",
            """Log.i(BENCHMARK_LOG_TAG, BENCHMARK_LOG_MARKER)
        Log.i("KmpBusiness", com.sickworm.jugg.demo.kmp.incrementalPlatformValue())""",
        )

        withPatchedKotlinFiles(common to commonSource, actual to actualSource, app to appSource) {
            val jugg = compileKmpChanges(common, actual, app)

            assertKmpCompileSuccess(jugg, common, actual)
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/DifferentAndroidIncrementalApiKt.dex") })
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/MainActivity.dex") })
            assertNoComposeGradle(jugg)

            changeAndRevert(common, "expect fun", "// cache expect edge\nexpect fun") {
                assertKmpCompileSuccess(compileKmpChanges(common), common, actual)
            }
            changeAndRevert(actual, "\"incremental\"", "\"incremental cache edge\"") {
                assertKmpCompileSuccess(compileKmpChanges(actual), common, actual)
            }
        }
    }

    @Test
    fun compileCommonSourceUsingBaselineCommonHelper() {
        val common = kmpSource("commonMain", "PlatformLabel.kt")
        val actual = kmpSource("androidMain", "PlatformLabel.android.kt")
        val helper = kmpSource("commonMain", "CommonPlatformHelper.kt")

        changeAndRevert(common, ":baseline", ":classpath changed") {
            val jugg = compileKmpChanges(common)

            assertKmpCompileSuccess(jugg, common, actual)
            assertFalse(jugg.readLatestProjectLog().contains(helper.absolutePath))
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileOrdinaryCommonSourceWithoutExpectActual() {
        val helper = kmpSource("commonMain", "CommonPlatformHelper.kt")

        changeAndRevert(helper, "\"common\"", "\"ordinary changed\"") {
            val jugg = compileKmpChanges(helper)

            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), jugg.readLatestProjectLog())
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/CommonPlatformHelperKt.dex") })
            assertFalse(jugg.readLatestProjectLog().contains("-Xcommon-sources="))
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun compileIntermediateSharedMainActual() {
        val common = kmpSource("commonMain", "SharedPlatformLabel.kt")
        val sharedActual = kmpSource("sharedMain", "SharedPlatformLabel.shared.kt")

        changeAndRevert(sharedActual, "\"Shared Android\"", "\"Shared changed\"") {
            val jugg = compileKmpChanges(sharedActual)

            assertKmpCompileSuccess(jugg, common, sharedActual)
            val log = jugg.readLatestProjectLog()
            val commonSourcesArg = log.lineSequence().firstOrNull { it.contains("-Xcommon-sources=") }.orEmpty()
            assertTrue(commonSourcesArg.contains(common.absolutePath), commonSourcesArg)
            assertTrue(commonSourcesArg.contains(sharedActual.absolutePath), commonSourcesArg)
            assertTrue(stagingDexPaths(jugg).any { it.endsWith("/SharedPlatformLabel.dex") })
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun keepKotlinCompilerFailureForNewExpectWithoutActual() {
        val common = kmpSource("commonMain", "OneSidedIncrementalApi.kt")
        val source = """
            package com.sickworm.jugg.demo.kmp

            expect fun oneSidedIncrementalValue(): String
        """.trimIndent()

        withPatchedKotlinFiles(common to source) {
            val jugg = compileKmpChanges(common)
            val log = jugg.readLatestProjectLog()

            assertEquals(listOf(common.canonicalFile), jugg.deployFileManager.getUncompiledFiles().map { it.file.canonicalFile })
            assertTrue(log.contains("oneSidedIncrementalValue") || log.contains("has no actual"), log)
            assertFalse(stagingDexPaths(jugg).any { it.endsWith("/OneSidedIncrementalApiKt.dex") })
            assertNoComposeGradle(jugg)
        }
    }

    @Test
    fun keepKotlinCompilerFailureWhenComplementaryCacheIsMissing() {
        val actual = kmpSource("androidMain", "PlatformLabel.android.kt")
        val cacheDir = File(
            projectInfo.projectRoot,
            "build/kmpCompose/kotlin/compileDebugKotlinAndroid/cacheable/caches-jvm/jvm/kotlin",
        )
        val backupDir = File(cacheDir.parentFile, "kotlin.jugg-test-backup")
        check(cacheDir.exists()) { "Kotlin complementary cache is missing before the test: $cacheDir" }
        check(!backupDir.exists()) { "Kotlin complementary cache backup already exists: $backupDir" }
        check(cacheDir.renameTo(backupDir)) { "Failed to move Kotlin complementary cache for the test" }
        try {
            changeAndRevert(actual, "\"Android\"", "\"Android without cache\"") {
                val jugg = compileKmpChanges(actual)
                val log = jugg.readLatestProjectLog()

                assertEquals(listOf(actual.canonicalFile), jugg.deployFileManager.getUncompiledFiles().map { it.file.canonicalFile })
                assertTrue(log.contains("actual") || log.contains("multiplatform"), log)
                assertFalse(stagingDexPaths(jugg).any { it.endsWith("/PlatformLabel.dex") })
                assertNoComposeGradle(jugg)
            }
        } finally {
            check(backupDir.renameTo(cacheDir)) { "Failed to restore Kotlin complementary cache after the test" }
        }
    }

    @Test
    fun keepComplementaryCacheAfterFailedCompilation() {
        val common = kmpSource("commonMain", "PlatformLabel.kt")
        val actual = kmpSource("androidMain", "PlatformLabel.android.kt")

        changeAndRevert(common, "expect object PlatformLabel", "expect object PlatformLabel : MissingType") {
            val failed = compileKmpChanges(common)
            assertEquals(
                listOf(common.canonicalFile),
                failed.deployFileManager.getUncompiledFiles().map { it.file.canonicalFile },
            )
        }
        changeAndRevert(actual, "\"Android\"", "\"Android after failed compile\"") {
            assertKmpCompileSuccess(compileKmpChanges(actual), common, actual)
        }
    }

    @Test
    fun compileBusinessExpectActualWithKotlin19() {
        compileBusinessExpectActualWithVersion("1.9")
    }

    @Test
    fun compileBusinessExpectActualWithKotlin23() {
        compileBusinessExpectActualWithVersion("2.3")
    }

    private fun newFixtureJugg() = MockJugg(
        compileCommand = FIXTURE_COMPILE_COMMAND,
        isIdeSynced = true,
    )

    private fun compileKmpChanges(vararg files: File): MockJugg {
        val jugg = newFixtureJugg()
        jugg.dryFullCompile()
        jugg.notifyFileChanges(files.toList())
        jugg.compileChangedFiles()
        return jugg
    }

    private fun compileBusinessExpectActualWithVersion(version: String) {
        try {
            GradleBuildHelper.switchKotlinVersion(version)
            assembleFixture()
            writeCommonMainIdeProjectInfo(version)
            val common = kmpSource("commonMain", "PlatformLabel.kt")
            val actual = kmpSource("androidMain", "PlatformLabel.android.kt")

            changeAndRevert(common, ":baseline", ":$version common") {
                val jugg = compileKmpChanges(common)
                assertKmpCompileSuccess(jugg, common, actual)
                assertNoComposeGradle(jugg)
            }
            changeAndRevert(actual, "\"Android\"", "\"$version Android\"") {
                val jugg = compileKmpChanges(actual)
                assertKmpCompileSuccess(jugg, common, actual)
                assertNoComposeGradle(jugg)
            }
        } finally {
            GradleBuildHelper.switchKotlinVersion("2.1")
            assembleFixture()
            writeCommonMainIdeProjectInfo("2.1")
        }
    }

    private fun kmpSource(sourceSet: String, fileName: String): File = File(
        projectInfo.projectRoot,
        "kmpCompose/src/$sourceSet/kotlin/com/sickworm/jugg/demo/kmp/$fileName",
    )

    private fun gradleComposeGeneratedSources(): List<File> {
        val gradleProjectInfo = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger).load()
            ?: error("KMP Compose Gradle project info was not generated")
        val module = gradleProjectInfo.modules["kmpCompose"]
            ?: error("kmpCompose module was not found in Gradle project info")
        return module.buildPathInfo.composeResourceGeneratedSourcePath.walkTopDown().asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private fun assertKmpCompileSuccess(jugg: MockJugg, vararg expectedSources: File) {
        val log = jugg.readLatestProjectLog()
        assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), log)
        assertTrue(log.contains("-Xmulti-platform"), log)
        assertTrue(log.contains("-Xcommon-sources="), log)
        expectedSources.forEach { source ->
            val relativePath = source.relativeTo(projectInfo.projectRoot).path.replace('\\', '/')
            assertTrue(
                log.contains(source.absolutePath) || log.contains(relativePath),
                "Missing ${source.absolutePath}:\n$log",
            )
        }
        val outputs = jugg.deployFileManager.getStagingFiles().filter {
            it.type == CompileOutput.Type.Dex && it.relativeFile.path.contains("com/sickworm/jugg/demo/kmp")
        }
        val apkPaths = jugg.compileContextManager.compileContext.apkInfos
            .flatMap { apk -> apk.files.map { it.apkFile.path } }
            .toSet()
        assertTrue(outputs.isNotEmpty(), log)
        assertTrue(outputs.all { output ->
            output.apkPath in apkPaths || output.targetApkPaths.any(apkPaths::contains)
        })
    }

    private fun stagingDexPaths(jugg: MockJugg): Set<String> = jugg.deployFileManager.getStagingFiles()
        .filter { it.type == CompileOutput.Type.Dex }
        .map { it.relativeFile.path.replace('\\', '/') }
        .toSet()

    private fun stagingAssetPaths(jugg: MockJugg): Set<String> = jugg.deployFileManager.getStagingFiles()
        .filter { it.type == CompileOutput.Type.Asset }
        .map { it.relativeFile.path.replace('\\', '/').substringAfter("assets/") }
        .toSet()

    private fun stagingRawAssetPaths(jugg: MockJugg): Set<String> = jugg.deployFileManager.getStagingFiles()
        .filter { it.type == CompileOutput.Type.Asset }
        .map { it.relativeFile.path.replace('\\', '/') }
        .toSet()

    private fun assertNoComposeGradle(jugg: MockJugg) {
        val log = jugg.readLatestProjectLog()
        assertFalse(log.contains("./gradlew"), log)
        assertFalse(log.contains("generateComposeResClass"), log)
        assertFalse(log.contains("generateResourceAccessors"), log)
        assertFalse(log.contains("prepareComposeResources"), log)
    }

    private fun withPatchedKotlinFiles(vararg patches: Pair<File, String>, block: () -> Unit) {
        val backups = patches.associate { (file, _) -> file to file.takeIf(File::exists)?.readBytes() }
        try {
            patches.forEach { (file, content) ->
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
            block()
        } finally {
            backups.forEach { (file, content) ->
                if (content == null) file.delete() else file.writeBytes(content)
            }
            val addedFiles = backups.filterValues { it == null }.keys
            if (addedFiles.isNotEmpty()) {
                val relativePaths = addedFiles.map { it.relativeTo(projectInfo.projectRoot).path }
                val process = ProcessBuilder(
                    listOf("git", "update-index", "--force-remove", "--") + relativePaths,
                ).directory(projectInfo.projectRoot).start()
                check(process.waitFor() == 0) { process.errorStream.bufferedReader().readText() }
            }
        }
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
