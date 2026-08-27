package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class TooManyChangesTest {

    @Test
    fun evaluate_sameModuleJavaAndKotlin_showsOneModule() {
        val module = ModuleInfo.virtualModule
        val javaFile = ChangedFile(CompileFile.Type.Java, File("/tmp/A.java"), File("/tmp"), module)
        val kotlinFile = ChangedFile(CompileFile.Type.Kotlin, File("/tmp/A.kt"), File("/tmp"), module)
        val original = JuggSettings.maxCompileSourceModules
        JuggSettings.maxCompileSourceModules = 1
        try {
            val info = TooManyChanges.evaluate(listOf(javaFile, kotlinFile))
            assertNotNull(info)
            assertEquals(1, info!!.moduleCount)
            assertEquals(1, info.javaFileCount)
            assertEquals(1, info.kotlinFileCount)
        } finally {
            JuggSettings.maxCompileSourceModules = original
        }
    }
}
