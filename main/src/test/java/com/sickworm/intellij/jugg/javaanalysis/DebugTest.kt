package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import org.junit.Test

class DebugTest {

    @Test
    fun debugJavaParser() {
        val dir = TestGlobal.projectRootDir.toPath()
        val analyzer = JavaParserAnalyzer(dir)
        
        println("=== JavaParser analyzing ${analyzer.totalFiles} files ===")
        val results = analyzer.analyze()
        
        println("=== JavaParser found ${results.size} files with references ===")
        results.forEach { result ->
            println("File: ${result.file}")
            result.references.forEach { ref ->
                println("  - $ref")
            }
        }
    }
    
    @Test
    fun debugJDT() {
        val dir = TestGlobal.projectRootDir.toPath()
        val analyzer = JDTAnalyzer(dir)
        
        println("=== JDT analyzing ${analyzer.totalFiles} files ===")
        val results = analyzer.analyze()
        
        println("=== JDT found ${results.size} files with references ===")
        results.forEach { result ->
            println("File: ${result.file}")
            result.references.forEach { ref ->
                println("  - $ref")
            }
        }
    }
}