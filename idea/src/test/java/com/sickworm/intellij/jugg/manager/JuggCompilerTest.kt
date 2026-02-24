package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.mock.androidApkPackage
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.Before
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
