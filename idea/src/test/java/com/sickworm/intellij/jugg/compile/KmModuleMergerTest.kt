package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.source.kotlin.KmModuleMerger
import com.sickworm.intellij.jugg.mock.assetsKotlinDir
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.clearBuild
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class KmModuleMergerTest {

    @Test
    fun testMerge() {
        clearBuild()

        val merger = KmModuleMerger()
        assertEquals("""
optionalAnnotationClasses: []
packageParts:

        """.trimIndent(), merger.toString())

        val newKmModuleFile = File(assetsKotlinDir, "kotlin_module/app_debug_new.kotlin_module")
        merger.merge(newKmModuleFile)
        assertEquals("""
optionalAnnotationClasses: []
packageParts:
   key: com.sickworm.jugg.demo.testcase.ktextension
   fileFacades: [com/sickworm/jugg/demo/testcase/ktextension/ExtClass1Kt]
   multiFileClassParts:

        """.trimIndent(), merger.toString())

        val oldKmModuleFile = File(assetsKotlinDir, "kotlin_module/app_debug_old.kotlin_module")
        merger.merge(oldKmModuleFile)
        assertEquals("""
optionalAnnotationClasses: []
packageParts:
   key: com.sickworm.jugg.demo.testcase.ktextension
   fileFacades: [com/sickworm/jugg/demo/testcase/ktextension/ExtClass1Kt, com/sickworm/jugg/demo/testcase/ktextension/ExtClass3Kt]
   multiFileClassParts:

        """.trimIndent(), merger.toString())

        val outputFile = File(buildDir, "app_debug_merged.kotlin_module")
        merger.writeTo(outputFile)

        val newMerger = KmModuleMerger()
        newMerger.merge(outputFile)
        assertEquals("""
optionalAnnotationClasses: []
packageParts:
   key: com.sickworm.jugg.demo.testcase.ktextension
   fileFacades: [com/sickworm/jugg/demo/testcase/ktextension/ExtClass1Kt, com/sickworm/jugg/demo/testcase/ktextension/ExtClass3Kt]
   multiFileClassParts:

        """.trimIndent(), newMerger.toString())
    }



    @Test
    fun testMerge2() {
        val merger = KmModuleMerger()
        val newKmModuleFile = File("/Users/wormchen/AndroidStudioProjects/Gradle8Test/app/build/tmp/kotlin-classes/debug/META-INF/app_debug.kotlin_module")
        merger.merge(newKmModuleFile)
        println("merge result: $merger")
    }
}