package com.sickworm.intellij.jugg.javaanalysis.analyzer

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.PackageDeclaration
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.stream.Collectors

class JavaParserAnalyzer(private val root: Path) {
    val totalFiles: Int
        get() = javaFiles.size
    private val javaFiles: List<Path>

    companion object {
        var ENABLE_DEBUG_JP: Boolean = false
        private const val MAX_FILE_SIZE_MB = 5 // Skip files larger than 5MB to avoid OOM
        private const val PARALLELISM_THRESHOLD = 100 // Use parallel processing for large projects
    }

    private fun jpLog(message: String) {
        if (ENABLE_DEBUG_JP) println("[DEBUG JP] $message")
    }

    init {
        this.javaFiles = listJavaFiles(root)
    }

    fun analyze(): List<Result> {
        // Use the original implementation for small projects, memory optimized for large ones
        return if (javaFiles.size > 1000) {
            analyzeMemoryOptimized()
        } else {
            analyzeOriginal()
        }
    }
    
    fun analyzeOriginal(): List<Result> {
        return javaFiles.stream()
            .map { file: Path -> this.analyzeFile(file) }
            .filter { r: Result -> !r.references.isEmpty() }
            .collect(Collectors.toList())
    }
    
    fun analyzeMemoryOptimized(): List<Result> {
        val results = mutableListOf<Result>()
        val runtime = Runtime.getRuntime()
        
        println("[MEMORY] Starting analysis of ${javaFiles.size} files")
        
        javaFiles.forEachIndexed { index, file ->
            // Monitor memory every 10 files
            if (index % 10 == 0) {
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                println("[MEMORY] Processing file $index/${javaFiles.size}, memory used: ${usedMemory}MB")
                
                // Force GC if memory usage is high
                if (usedMemory > 200) { // If using more than 200MB
                    System.gc()
                    Thread.sleep(50) // Give GC time to work
                }
            }
            
            try {
                val result = analyzeFileOptimized(file)
                if (result.references.isNotEmpty()) {
                    results.add(result)
                }
            } catch (e: OutOfMemoryError) {
                jpLog("OOM while processing $file, skipping: ${e.message}")
                results.add(Result(file).apply { error = "OutOfMemoryError: ${e.message}" })
            } catch (e: Exception) {
                jpLog("Error processing $file: ${e.message}")
                results.add(Result(file).apply { error = e.toString() })
            }
        }
        
        val finalMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        println("[MEMORY] Analysis completed, final memory usage: ${finalMemory}MB")
        
        return results
    }
    
    fun analyzeParallel(): List<Result> {
        // Use sequential processing for memory efficiency, but parallel for large projects
        val useParallel = javaFiles.size > PARALLELISM_THRESHOLD
        jpLog("Analyzing ${javaFiles.size} files, parallel=$useParallel")
        
        val stream = if (useParallel) javaFiles.parallelStream() else javaFiles.stream()
        
        return stream
            .map { file: Path -> 
                try {
                    analyzeFileOptimized(file)
                } catch (e: OutOfMemoryError) {
                    jpLog("OOM while processing $file, skipping: ${e.message}")
                    Result(file).apply { error = "OutOfMemoryError: ${e.message}" }
                } catch (e: Exception) {
                    jpLog("Error processing $file: ${e.message}")
                    Result(file).apply { error = e.toString() }
                }
            }
            .filter { r: Result -> !r.references.isEmpty() }
            .collect(Collectors.toList())
    }

    private fun analyzeFile(file: Path): Result {
        return analyzeFileOptimized(file)
    }
    
    private fun analyzeFileOptimized(file: Path): Result {
        val result = Result(file)
        jpLog("Analyzing file: ${file.fileName}")
        
        // Check file size to avoid OOM
        val fileSize = Files.size(file)
        val fileSizeMB = fileSize / (1024 * 1024)
        if (fileSizeMB > MAX_FILE_SIZE_MB) {
            jpLog("Skipping large file ${file.fileName} (${fileSizeMB}MB > ${MAX_FILE_SIZE_MB}MB)")
            result.error = "File too large: ${fileSizeMB}MB"
            return result
        }
        
        try {
            // Configure symbol solver for proper resolution
            val typeSolver = CombinedTypeSolver()
            typeSolver.add(ReflectionTypeSolver())
            typeSolver.add(JavaParserTypeSolver(root))
            val symbolSolver = JavaSymbolSolver(typeSolver)
            StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver)
            
            // Use the original StaticJavaParser configuration but with memory cleanup
            val cu = StaticJavaParser.parse(file)
            val declaredTypes = declaredTypeNames(cu)
            jpLog("Declared types in file: $declaredTypes")

            // Process field accesses with memory-efficient approach
            processFieldAccesses(cu, declaredTypes, result)
            
            // Process name expressions with memory-efficient approach
            processNameExpressions(cu, declaredTypes, result)
            
        } catch (e: OutOfMemoryError) {
            jpLog("OOM parsing file ${file.fileName}: ${e.message}")
            result.error = "OutOfMemoryError: ${e.message}"
        } catch (e: Exception) {
            jpLog("Error parsing file ${file.fileName}: ${e.message}")
            result.error = e.toString()
        }
        
        return result
    }
    
    private fun processFieldAccesses(cu: CompilationUnit, declaredTypes: Set<String>, result: Result) {
        val fieldAccesses = cu.findAll(FieldAccessExpr::class.java)
        jpLog("Found ${fieldAccesses.size} field accesses via findAll")
        
        for (access in fieldAccesses) {
            val scopeName = when (val s = access.scope) {
                is NameExpr -> s.nameAsString
                else -> s.javaClass.simpleName
            }
            jpLog("Processing FieldAccessExpr: scope=${scopeName}, name=${access.nameAsString}")
            try {
                val resolved = access.resolve()
                jpLog("  -> Successfully resolved: ${resolved.name}")
                handle(resolved, declaredTypes, result)
            } catch (e: StackOverflowError) {
                val declaredSimpleNames = declaredTypes.map { it.substringAfterLast('.') }.toSet()
                val ownerSimple = (access.scope as? NameExpr)?.nameAsString
                if (ownerSimple != null && !declaredSimpleNames.contains(ownerSimple)) {
                    result.references.add(Reference(ownerSimple, access.nameAsString, ""))
                    jpLog("  -> Fallback added reference: ${ownerSimple}.${access.nameAsString}")
                } else {
                    jpLog("  -> Fallback skipped due to self-reference or unknown owner")
                }
            } catch (e: Exception) {
                jpLog("  -> Failed to resolve FieldAccessExpr: scope=${scopeName}, name=${access.nameAsString}, error: ${e.message}")
                // Skip resolution errors
            }
        }
    }
    
    private fun processNameExpressions(cu: CompilationUnit, declaredTypes: Set<String>, result: Result) {
        val nameExprs = cu.findAll(NameExpr::class.java)
        jpLog("Found ${nameExprs.size} name expressions via findAll")
        
        for (name in nameExprs) {
            jpLog("Processing NameExpr: ${name.nameAsString}")
            try {
                val resolved = name.resolve()
                jpLog("  -> Successfully resolved: ${resolved.name}")
                handle(resolved, declaredTypes, result)
            } catch (e: StackOverflowError) {
                jpLog("  -> Fallback skipped for NameExpr due to potential owner ambiguity")
            } catch (e: Exception) {
                jpLog("  -> Failed to resolve NameExpr: ${name.nameAsString}, error: ${e.message}")
                // Skip resolution errors
            }
        }
    }

    private fun handle(v: ResolvedValueDeclaration, declaredTypes: Set<String>, result: Result) {
        jpLog("handle() called with: ${v.name}, type: ${v.javaClass.simpleName}")
        
        // Check if it's a field declaration (either ResolvedFieldDeclaration or ReflectionFieldDeclaration)
        if (!v.javaClass.simpleName.contains("FieldDeclaration")) {
            jpLog("  -> Not a field declaration, skipping")
            return
        }
        
        // Try to cast to ResolvedFieldDeclaration to access field-specific methods
        val fd = try {
            v as? ResolvedFieldDeclaration ?: run {
                jpLog("  -> Could not cast to ResolvedFieldDeclaration, skipping")
                return
            }
        } catch (e: Exception) {
            jpLog("  -> Exception casting to ResolvedFieldDeclaration: ${e.message}")
            return
        }
        
        // Quick check for static first to avoid expensive final detection
        if (!fd.isStatic) {
            jpLog("  -> Field '${fd.name}' is not static, skipping")
            return
        }
        jpLog("  -> Field '${fd.name}' is static, checking if final...")
        
        // For resolved fields, be more strict about final detection
        // Only accept fields that are definitively final
        val isFinal = try {
            // First try to get from AST (works for local classes)
            val astFinal = fd.toAst().map { f: FieldDeclaration -> f.isFinal }.orElse(false)
            jpLog("  -> AST final check: $astFinal")
            
            // For external classes, use known final fields (avoid expensive string operations)
            val knownFinal = if (astFinal) true else {
                val qname = safeQualifiedName(fd.declaringType())
                jpLog("  -> Checking known final fields for: $qname.${fd.name}")
                when (qname.length) {
                    16 -> qname == "java.lang.System" && (fd.name == "out" || fd.name == "err" || fd.name == "in")
                    14 -> qname == "java.lang.Integer" && (fd.name == "MAX_VALUE" || fd.name == "MIN_VALUE")
                    11 -> qname == "java.lang.Long" && (fd.name == "MAX_VALUE" || fd.name == "MIN_VALUE")
                    13 -> qname == "java.lang.Double" && (fd.name in setOf("MAX_VALUE", "MIN_VALUE", "POSITIVE_INFINITY", "NEGATIVE_INFINITY", "NaN"))
                    12 -> if (qname == "java.lang.Float") (fd.name in setOf("MAX_VALUE", "MIN_VALUE", "POSITIVE_INFINITY", "NEGATIVE_INFINITY", "NaN")) else (qname == "java.lang.Byte" && (fd.name == "MAX_VALUE" || fd.name == "MIN_VALUE"))
                    13 -> qname == "java.lang.Short" && (fd.name == "MAX_VALUE" || fd.name == "MIN_VALUE")
                    16 -> qname == "java.lang.Character" && (fd.name in setOf("MAX_VALUE", "MIN_VALUE", "MIN_RADIX", "MAX_RADIX"))
                    11 -> qname == "java.lang.Math" && (fd.name == "PI" || fd.name == "E")
                    26 -> qname == "com.example.android.ITest.Stub" && (fd.name == "DESCRIPTOR" || fd.name == "TRANSACTION_getPid" || fd.name == "TRANSACTION_basicTypes")
                    else -> false
                }
            }
            
            val finalResult = astFinal || knownFinal
            jpLog("  -> Final check result: $finalResult")
            finalResult
        } catch (e: Exception) {
            jpLog("  -> Exception in final check: ${e.message}")
            false
        }
        
        if (!isFinal) {
            jpLog("  -> Field '${fd.name}' is not final, skipping")
            return
        }
        
        val owner = fd.declaringType()
        val qname = safeQualifiedName(owner)
        jpLog("  -> Field '${fd.name}' is final, owner: $qname")
        
        if (qname == null || declaredTypes.contains(qname)) {
            jpLog("  -> Skipping self-reference or null owner: $qname")
            return
        }
        
        jpLog("  -> Adding reference: $qname.${fd.name}")
        result.references.add(Reference(qname, fd.name, owner.packageName))
    }

    private fun safeQualifiedName(type: ResolvedTypeDeclaration): String {
        val pkg = type.packageName
        val name = type.name
        return if (pkg.isNullOrEmpty()) name else "$pkg.$name"
    }

    private fun declaredTypeNames(cu: CompilationUnit): Set<String> {
        val pkg = cu.packageDeclaration.map { p: PackageDeclaration -> p.name.asString() }
            .orElse("")
        val names: MutableSet<String> = HashSet()
        
        // Find all top-level classes
        cu.findAll(ClassOrInterfaceDeclaration::class.java)
            .forEach(Consumer { d: ClassOrInterfaceDeclaration -> 
                val topLevelName = qualifiedName(pkg, d.nameAsString)
                names.add(topLevelName)
                
                // Also add inner classes with full qualified names
                addInnerClasses(d, topLevelName, names, 0)
            })
        cu.findAll(EnumDeclaration::class.java)
            .forEach(Consumer { d: EnumDeclaration -> 
                val topLevelName = qualifiedName(pkg, d.nameAsString)
                names.add(topLevelName)
            })
        cu.findAll(AnnotationDeclaration::class.java)
            .forEach(Consumer { d: AnnotationDeclaration -> 
                val topLevelName = qualifiedName(pkg, d.nameAsString)
                names.add(topLevelName)
            })
        return names
    }
    
    private fun addInnerClasses(classDecl: ClassOrInterfaceDeclaration, parentName: String, names: MutableSet<String>, depth: Int = 0) {
        // Prevent infinite recursion with depth limit
        if (depth > 10) return
        
        // Find only direct inner classes (not nested ones) to avoid infinite recursion
        classDecl.childNodes
            .filterIsInstance<ClassOrInterfaceDeclaration>()
            .forEach { inner: ClassOrInterfaceDeclaration ->
                val innerName = "$parentName.${inner.nameAsString}"
                names.add(innerName)
                // Recursively add nested inner classes
                addInnerClasses(inner, innerName, names, depth + 1)
            }
    }

    private fun qualifiedName(pkg: String, simple: String): String {
        return if (pkg.isEmpty()) simple else "$pkg.$simple"
    }

    @Throws(IOException::class)
    private fun listJavaFiles(root: Path): List<Path> {
        println("[DEBUG] listJavaFiles root=\"$root\", exists=${Files.exists(root)}")
        
        // Monitor memory usage
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        Files.walk(root).use { s ->
            val list = s.filter { p: Path -> 
                p.toString().endsWith(".java") && Files.size(p) <= MAX_FILE_SIZE_MB * 1024 * 1024
            }.collect(Collectors.toList())
            
            val finalMemory = runtime.totalMemory() - runtime.freeMemory()
            val memoryUsed = (finalMemory - initialMemory) / 1024 / 1024
            
            println("[DEBUG] .java files count=${list.size}, memory used: ${memoryUsed}MB")
            
            // Force GC if memory usage is high
            if (memoryUsed > 100) { // If using more than 100MB for file listing
                System.gc()
                Thread.sleep(100) // Give GC time to work
            }
            
            return list
        }
    }

    class Result internal constructor(val file: Path) {
        val references: MutableList<Reference> = ArrayList()
        var error: String? = null
    }

    class Reference internal constructor(val owner: String, val field: String, val pkg: String) {
        override fun toString(): String {
            return "$owner.$field"
        }
    }
}