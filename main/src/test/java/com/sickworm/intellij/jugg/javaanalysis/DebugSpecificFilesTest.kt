package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import org.junit.Test

class DebugSpecificFilesTest {
    
    @Test
    fun debugSpecificFiles() {
        val root = TestGlobal.projectRootDir.toPath()
        
        val javaParser = JavaParserAnalyzer(root)
        val jdt = JDTAnalyzer(root)
        
        println("=== Total files to analyze ===")
        println("JavaParser total files: ${javaParser.totalFiles}")
        println("JDT total files: ${jdt.totalFiles}")
        
        val javaParserResults = javaParser.analyze()
        val jdtResults = jdt.analyze()
        
        // Check specific files
        val targetFiles = listOf(
            TestGlobal.projectRootDir.path + "/app/src/main/java/com/sickworm/jugg/demo/testcase/defaultinterface/InvokerClass2.java",
            TestGlobal.projectRootDir.path + "/app/src/main/java/com/sickworm/jugg/demo/testcase/desugar/JavaInvoker.java"
        )
        
        targetFiles.forEach { filePath ->
            val jpResult = javaParserResults.find { it.file.toString() == filePath }
            val jdtResult = jdtResults.find { it.file.toString() == filePath }
            
            println("\n=== File: $filePath ===")
            println("JavaParser: ${if (jpResult != null) "FOUND (${jpResult.references.size} references)" else "NOT FOUND"}")
            println("JDT: ${if (jdtResult != null) "FOUND (${jdtResult.references.size} references)" else "NOT FOUND"}")
            
            if (jpResult != null) {
                println("JavaParser references:")
                jpResult.references.forEach { ref ->
                    println("  - ${ref.owner}.${ref.field}")
                }
            }
            
            if (jdtResult != null) {
                println("JDT references:")
                jdtResult.references.forEach { ref ->
                    println("  - ${ref.owner}.${ref.field}")
                }
            }
        }
    }
}