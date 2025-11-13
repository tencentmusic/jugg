package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JDTAnalyzer
import com.sickworm.intellij.jugg.javaanalysis.analyzer.JavaParserAnalyzer
import org.junit.Test
import kotlin.test.assertEquals

class ConsistencyTest {

    @Test
    @Throws(Exception::class)
    fun javaParserAndJDTShouldReturnSameResults() {
        // Enable debug output for JavaParser
        JavaParserAnalyzer.ENABLE_DEBUG_JP = true
        
        val dir = TestGlobal.projectRootDir.toPath()

        val jpAnalyzer = JavaParserAnalyzer(dir)
        val jdtAnalyzer = JDTAnalyzer(dir)
        
        println("=== Total files to analyze ===")
        println("JavaParser total files: ${jpAnalyzer.totalFiles}")
        println("JDT total files: ${jdtAnalyzer.totalFiles}")

        val jpResults  = jpAnalyzer.analyze()
        val jdtResults = jdtAnalyzer.analyze()

        // Print all references found by each parser for debugging
        println("\n=== JavaParser found ${jpResults.size} files with references ===")
        jpResults.forEach { result ->
            println("File: ${result.file}")
            result.references.forEach { ref ->
                println("  - $ref")
            }
        }
        
        println("\n=== JDT found ${jdtResults.size} files with references ===")
        jdtResults.forEach { result ->
            println("File: ${result.file}")
            result.references.forEach { ref ->
                println("  - $ref")
            }
        }

        // 1. Result counts must match
        if (jpResults.size != jdtResults.size) {
            println("Size mismatch: JavaParser found ${jpResults.size} files, JDT found ${jdtResults.size} files")
            
            // Find files only in JavaParser
            val javaParserFiles = jpResults.map { it.file }.toSet()
            val jdtFiles = jdtResults.map { it.file }.toSet()
            
            val onlyInJavaParser = javaParserFiles - jdtFiles
            val onlyInJDT = jdtFiles - javaParserFiles
            
            println("\n=== Only in JavaParser (${onlyInJavaParser.size}) ===")
            onlyInJavaParser.forEach { file ->
                val result = jpResults.find { it.file == file }
                println("File: $file")
                result?.references?.forEach { ref ->
                    println("  - ${ref.owner}.${ref.field}")
                }
            }
            
            println("\n=== Only in JDT (${onlyInJDT.size}) ===")
            onlyInJDT.forEach { file ->
                val result = jdtResults.find { it.file == file }
                println("File: $file")
                result?.references?.forEach { ref ->
                    println("  - ${ref.owner}.${ref.field}")
                }
            }
        }
        assertEquals(jpResults.size, jdtResults.size, "Result counts are inconsistent")

        // 2. Sort by file path and compare references for each entry
        val jpSorted  = jpResults.sortedBy  { it.file.toString() }
        val jdtSorted = jdtResults.sortedBy { it.file.toString() }

        jpSorted.zip(jdtSorted).forEach { (jp, jdt) ->
            assertEquals(jp.file, jdt.file, "File paths are inconsistent")
            // Compare references after sorting by string value
            assertEquals(
                jp.references.map { it.toString() }.sorted(),
                jdt.references.map { it.toString() }.sorted(),
                "Reference list of ${jp.file} is inconsistent"
            )
        }
    }
}