package com.sickworm.intellij.jugg.manager

import org.junit.Test

class DeployManagerTest: BasicJuggMock() {

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
        changeFileAndNotify("TestNewJavaFile.java" to "TestNewJavaFile.java")
        checkCompileResult("TestNewJavaFile.java", newClassesSize = 1)
    }

    @Test
    fun testJavaClassAddMultiple() {
        changeFileAndNotify(
            "TestNewJavaFile.java" to "TestNewJavaFile.java",
            "TestNewJavaFile2.java" to "TestNewJavaFile2.java")
        checkCompileResult("TestNewJavaFile.java", "TestNewJavaFile2.java", newClassesSize = 2)
    }

    @Test
    fun testJavaClassChangeSignature() {
        changeFileAndNotify("MainActivity2.changeSignature.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity2.changeSignature.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    // kotlin class

    @Test
    fun testKotlinClassAddSingle() {
        changeFileAndNotify("TestNewKotlinFile.kt" to "TestNewKotlinFile.kt")
        checkCompileResult("TestNewKotlinFile.kt", newClassesSize = 1)
    }

    @Test
    fun testKotlinClassAddMultiple() {
        changeFileAndNotify(
            "TestNewKotlinFile.kt" to "TestNewKotlinFile.kt",
            "TestNewKotlinFile2.kt" to "TestNewKotlinFile2.kt")
        checkCompileResult("TestNewKotlinFile.kt", "TestNewKotlinFile2.kt", newClassesSize = 2)
    }

    @Test
    fun testKotlinClassChangeSignature() {
        changeFileAndNotify("MainActivity.changeSignature.kt" to "MainActivity.kt")
        // there is a inner class inside MainActivity.kt
        checkCompileResult("MainActivity.kt",
            hotReloadModifiedClassesSize = 1,
            hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity.changeSignature.kt" to "MainActivity.kt")
        checkCompileResult("MainActivity.kt", hotReloadModifiedClassesSize = 2)
    }

    // java method

    @Test
    fun testJavaMethodAddSingle() {
        changeFileAndNotify("MainActivity2.addMethod.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity2.addMethod.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodRemoveSingle() {
        changeFileAndNotify("MainActivity2.removeMethod.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity2.removeMethod.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeReturn() {
        changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeArgument() {
        changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeReturnThenChangeArgument() {
        changeFileAndNotify("MainActivity2.changeMethodReturn.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        // second time deploy will be hot reload
        changeFileAndNotify("MainActivity2.changeMethodArgument.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)
    }

    @Test
    fun testJavaMethodChangeContent() {
        changeFileAndNotify("MainActivity2.changeContent.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }

    // java static method, skip because I don't have static method on demo project, and
    // I need to implement auto-build on demo project apk first

    // kotlin method, skip because I don't want to write now

    // java variable

    @Test
    fun testJavaVariableAdd() {
        changeFileAndNotify("MainActivity2.addVariable.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotFixModifiedClassesSize = 1)

        // simulate deploy
        val deployData = deployDataManager.getDeployData()
        deployDataManager.commit(deployData)

        changeFileAndNotify("MainActivity2.addVariable.java" to "MainActivity2.java")
        checkCompileResult("MainActivity2.java", hotReloadModifiedClassesSize = 1)
    }
}
