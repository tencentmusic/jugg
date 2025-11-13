package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Test

class CompareFilesTest {
    
    @Test
    fun compareFileLists() {
        val root = TestGlobal.projectRootDir.toPath()
        
        val javaParser = JavaParserAnalyzer(root)
        val jdt = JDTAnalyzer(root)
        
        val javaParserResults = javaParser.analyze()
        val jdtResults = jdt.analyze()
        
        println("=== JavaParser files (${javaParserResults.size}) ===")
        javaParserResults.forEach { result ->
            if (result.references.isNotEmpty()) {
                println("File: ${result.file}")
                result.references.forEach { ref ->
                    println("  - ${ref.owner}.${ref.field}")
                }
            }
        }
        
        println("\n=== JDT files (${jdtResults.size}) ===")
        jdtResults.forEach { result ->
            if (result.references.isNotEmpty()) {
                println("File: ${result.file}")
                result.references.forEach { ref ->
                    println("  - ${ref.owner}.${ref.field}")
                }
            }
        }
        
        // Find files only in JavaParser
        val javaParserFiles = javaParserResults.filter { it.references.isNotEmpty() }.map { it.file }.toSet()
        val jdtFiles = jdtResults.filter { it.references.isNotEmpty() }.map { it.file }.toSet()
        
        val onlyInJavaParser = javaParserFiles - jdtFiles
        val onlyInJDT = jdtFiles - javaParserFiles
        
        println("\n=== Only in JavaParser (${onlyInJavaParser.size}) ===")
        onlyInJavaParser.forEach { file -> println(file) }
        
        println("\n=== Only in JDT (${onlyInJDT.size}) ===")
        onlyInJDT.forEach { file -> println(file) }
    }
}