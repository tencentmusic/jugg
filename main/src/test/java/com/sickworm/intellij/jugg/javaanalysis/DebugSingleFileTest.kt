package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import org.junit.Test

class DebugSingleFileTest {

    @Test
    fun debugABCFile() {
        // Enable debug output for JavaParser
        JavaParserAnalyzer.ENABLE_DEBUG_JP = true
        
        val root = TestGlobal.projectRootDir.toPath()
        
        val jpAnalyzer = JavaParserAnalyzer(root)
        val results = jpAnalyzer.analyze()
        
        val abcResult = results.find { it.file.toString().contains("ABC.java") }
        
        println("=== JavaParser analysis of ABC.java ===")
        if (abcResult != null) {
            println("File: ${abcResult.file}")
            println("References found: ${abcResult.references.size}")
            abcResult.references.forEach { ref ->
                println("  - $ref")
            }
        } else {
            println("ABC.java not found in results")
        }
    }
    
    @Test
    fun debugJDTABCFile() {
        // Enable debug output for JDT
        JDTAnalyzer.ENABLE_DEBUG_JDT = true
        
        val root = TestGlobal.projectRootDir.toPath()
        
        val jdtAnalyzer = JDTAnalyzer(root)
        val results = jdtAnalyzer.analyze()
        
        val abcResult = results.find { it.file.toString().contains("ABC.java") }
        
        println("=== JDT analysis of ABC.java ===")
        if (abcResult != null) {
            println("File: ${abcResult.file}")
            println("References found: ${abcResult.references.size}")
            abcResult.references.forEach { ref ->
                println("  - $ref")
            }
        } else {
            println("ABC.java not found in results")
        }
    }
}