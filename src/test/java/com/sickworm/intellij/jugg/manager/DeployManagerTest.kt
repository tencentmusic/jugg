package com.sickworm.intellij.jugg.manager

import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class DeployManagerTest {

    companion object {
        private val juggMock = BasicJuggMock()

        @BeforeClass
        @JvmStatic
        fun initEnv() {
            juggMock.initEnv()
        }
    }

    @Before
    fun resetAllState() {
        juggMock.resetAllState()
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
        juggMock.changeFileAndNotify("TestNewJavaFile.java" to "TestNewJavaFile.java")
        juggMock.checkCompileResult("TestNewJavaFile.java", newClassesSize = 1)
    }

    @Test
    fun testJavaClassAddMultiple() {
        juggMock.changeFileAndNotify(
            "TestNewJavaFile.java" to "TestNewJavaFile.java",
            "TestNewJavaFile2.java" to "TestNewJavaFile2.java")
        juggMock.checkCompileResult("TestNewJavaFile.java", "TestNewJavaFile2.java", newClassesSize = 2)
    }

    @Test
    fun testJavaClassChangeSignature() {
        juggMock.changeFileAndNotify("MainActivity2.changeSignature.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity2.changeSignature.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    // kotlin class

    @Test
    fun testKotlinClassAddSingle() {
        juggMock.changeFileAndNotify("TestNewKotlinFile.kt" to "TestNewKotlinFile.kt")
        juggMock.checkCompileResult("TestNewKotlinFile.kt", newClassesSize = 1)
    }

    @Test
    fun testKotlinClassAddMultiple() {
        juggMock.changeFileAndNotify(
            "TestNewKotlinFile.kt" to "TestNewKotlinFile.kt",
            "TestNewKotlinFile2.kt" to "TestNewKotlinFile2.kt")
        juggMock.checkCompileResult("TestNewKotlinFile.kt", "TestNewKotlinFile2.kt", newClassesSize = 2)
    }

    @Test
    fun testKotlinClassChangeSignature() {
        juggMock.changeFileAndNotify("MainActivity.changeSignature.kt" to "MainActivity.kt")
        // there is a inner class inside MainActivity.kt
        juggMock.checkCompileResult("MainActivity.kt",
            hotReloadModifiedClassesSize = 1,
            hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity.changeSignature.kt" to "MainActivity.kt")
        juggMock.checkCompileResult("MainActivity.kt", hotReloadModifiedClassesSize = 2)
    }

    // java method

    @Test
    fun testJavaMethodAddSingle() {
        juggMock.changeFileAndNotify("MainActivity2.addMethod.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity2.addMethod.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodRemoveSingle() {
        juggMock.changeFileAndNotify("MainActivity2.removeMethod.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity2.removeMethod.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeReturn() {
        juggMock.changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeArgument() {
        juggMock.changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeReturnThenChangeArgument() {
        juggMock.changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        // second time deploy will be hot reload
        juggMock.changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeContent() {
        juggMock.changeFileAndNotify("MainActivity2.changeContent.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    // java static method, skip because I don't have static method on demo project, and
    // I need to implement auto-build on demo project apk first

    // kotlin method, skip because I don't want to write now

    // java variable

    @Test
    fun testJavaVariableAdd() {
        juggMock.changeFileAndNotify("MainActivity2.addVariable.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        juggMock.dryDeploy()

        juggMock.changeFileAndNotify("MainActivity2.addVariable.java" to "MainActivity2.java")
        juggMock.checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }
}
