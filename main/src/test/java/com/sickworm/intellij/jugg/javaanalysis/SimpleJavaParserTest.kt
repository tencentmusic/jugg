package com.sickworm.intellij.jugg.javaanalysis

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr

fun main() {
    val file = TestGlobal.projectRootDir.toPath().resolve("app/src/main/java/com/sickworm/jugg/demo/testcase/desugar/JavaInvoker.java")
    println("=== Analyzing file: ${file.fileName} ===")
    
    try {
        val cu = StaticJavaParser.parse(file)
        println("Compilation unit parsed successfully")
        
        val fieldAccesses = cu.findAll(FieldAccessExpr::class.java)
        println("Found ${fieldAccesses.size} FieldAccessExpr nodes:")
        fieldAccesses.forEach { expr ->
            println("  - ${expr.name} (scope: ${expr.scope})")
        }
        
        val nameExprs = cu.findAll(NameExpr::class.java)
        println("Found ${nameExprs.size} NameExpr nodes:")
        nameExprs.forEach { expr ->
            println("  - ${expr.name}")
        }
        
        // Also look for MethodCallExpr that might contain field references
        val methodCalls = cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr::class.java)
        println("Found ${methodCalls.size} MethodCallExpr nodes:")
        methodCalls.forEach { call ->
            println("  - ${call.name} (scope: ${call.scope})")
        }
        
    } catch (e: Exception) {
        println("Error parsing file: ${e.message}")
        e.printStackTrace()
    }
}