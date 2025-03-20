package com.sickworm.intellij.jugg.compile.databinding

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenBaseClassesCompiler
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataBindingCompileTest {

    @Test
    fun test() {
        buildDir.deleteRecursively()
        val compileTask = CompileTask(
            listOf(CompileFile(
                CompileFile.Type.Resource,
                File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_java_demo.xml"),
                File(assetsAndroidDir, "app/src/main/res"),
                context.modules.values.first()
            )),
            buildDir,
            CompileStatusHolder.DEFAULT,
        )

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        assertEquals(result.outputs.size, 1)
        assertEquals(
            File(compileTask.outputDir, "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.java"),
            result.outputs.first().file)

        val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
        val result2 = mapperCompiler.compile(compileTask)
        assertTrue(result2.isAllSuccess)
    }

}