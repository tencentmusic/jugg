package com.sickworm.intellij.jugg.javaanalysis.analyzer

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.*
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

class JDTAnalyzer(private val root: Path) {
    val totalFiles: Int
        get() = javaFiles.size
    private val javaFiles: List<Path>

    companion object {
        @JvmStatic var ENABLE_DEBUG_JDT: Boolean = false
    }

    private fun jdtLog(message: String) {
        if (ENABLE_DEBUG_JDT) println("[DEBUG JDT] $message")
    }

    init {
        this.javaFiles = listJavaFiles(root)
    }

    fun analyze(): List<Result> {
        return javaFiles.stream()
            .map { file: Path ->
                jdtLog("Processing file: $file")
                val result = this.analyzeFile(file)
                jdtLog("File $file has ${result.references.size} references")

                // Debug output for DefaultInterface.java and ABC.java
                if (file.toString().contains("DefaultInterface.java") || file.toString().contains("ABC.java")) {
                    jdtLog("${file.fileName}: found ${result.references.size} references")
                    result.references.forEach { ref ->
                        jdtLog("  - ${ref.owner}.${ref.field}")
                    }
                }

                result
            }
            .filter { r: Result ->
                val hasRefs = !r.references.isEmpty()
                if (hasRefs) {
                    jdtLog("Including file ${r.file} with ${r.references.size} references")
                }
                hasRefs
            }
            .collect(Collectors.toList())
    }

    private fun analyzeFile(file: Path): Result {
        val result = Result(file)
        val processedFieldBindings = mutableSetOf<String>() // Track processed field bindings to avoid double-counting
        try {
            val source = Files.readString(file)
            val parser = ASTParser.newParser(AST.JLS_Latest)
            parser.setResolveBindings(true)
            parser.setBindingsRecovery(true)

            // Add Java runtime to classpath to resolve System.out and other standard classes
            val javaHome = System.getProperty("java.home")
            val rtJar = Path.of(javaHome, "lib", "rt.jar")
            val jrtFs = Path.of(javaHome, "lib", "jrt-fs.jar")
            val classPath = if (Files.exists(rtJar)) {
                arrayOf(rtJar.toString(), root.toString())
            } else if (Files.exists(jrtFs)) {
                // Java 9+ uses jrt-fs.jar
                arrayOf(jrtFs.toString(), root.toString())
            } else {
                arrayOf(root.toString())
            }

            parser.setEnvironment(arrayOf(), classPath, null, true)
            parser.setUnitName(file.fileName.toString())
            parser.setSource(source.toCharArray())
            val options: MutableMap<String, String> = HashMap()
            options[JavaCore.COMPILER_COMPLIANCE] = JavaCore.VERSION_11
            options[JavaCore.COMPILER_SOURCE] = JavaCore.VERSION_11
            options[JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM] = JavaCore.VERSION_11
            parser.setCompilerOptions(options)

            val cu = parser.createAST(null) as CompilationUnit
            val declaredTypes = declaredTypeNames(cu)

            // Special debug for specific files
            if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                jdtLog("=== SPECIAL DEBUG FOR ${file.fileName} ===")
            }

            cu.accept(object : ASTVisitor() {
                override fun visit(node: QualifiedName): Boolean {
                    if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                        jdtLog("Visiting QualifiedName: $node")
                    }

                    // Skip processing QualifiedName if it's part of a FieldAccess to avoid double-counting
                    // Process field accesses only through the FieldAccess visitor
                    if (node.parent is FieldAccess) {
                        if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                            jdtLog("Skipping QualifiedName that's part of FieldAccess: $node")
                        }
                        return true
                    }

                    try {
                        val binding = node.resolveBinding()
                        if (binding != null) {
                            handle(binding, declaredTypes, result)
                        } else {
                            // Handle known static final fields that JDT can't resolve
                            handleKnownStaticFinalFields(node, declaredTypes, result)
                            if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                                jdtLog("QualifiedName binding is null: $node")
                            }
                        }
                    } catch (e: Exception) {
                        if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                            jdtLog("Error resolving QualifiedName: $node, error: ${e.message}")
                        }
                    }
                    return true // Continue visiting
                }

                override fun visit(node: SimpleName): Boolean {
                    if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                        jdtLog("Visiting SimpleName: $node")
                    }

                    // Skip processing SimpleName if it's part of a FieldAccess or QualifiedName to avoid double-counting
                    // Process field accesses only through the QualifiedName visitor
                    if (node.parent is FieldAccess || node.parent is QualifiedName) {
                        if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                            jdtLog("Skipping SimpleName that's part of FieldAccess/QualifiedName: $node")
                        }
                        return true
                    }

                    try {
                        val b = node.resolveBinding()
                        if (b is IVariableBinding) {
                            handle(b, declaredTypes, result)
                        } else if (b == null && file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                            jdtLog("SimpleName binding is null: $node")
                        }
                    } catch (e: Exception) {
                        if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                            jdtLog("Error resolving SimpleName: $node, error: ${e.message}")
                        }
                    }
                    return true // Continue visiting
                }

                override fun visit(node: FieldAccess): Boolean {
                    if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker") || file.toString().contains("DefaultInterface.java") || file.toString().contains("ABC.java")) {
                        jdtLog("Visiting FieldAccess: $node")
                    }

                    try {
                        val binding = node.resolveFieldBinding()
                        if (binding != null) {
                            handle(binding, declaredTypes, result)
                        } else {
                            if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker") || file.toString().contains("DefaultInterface.java") || file.toString().contains("ABC.java")) {
                                jdtLog("FieldAccess binding is null: $node")
                            }
                        }
                    } catch (e: Exception) {
                        if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker") || file.toString().contains("DefaultInterface.java") || file.toString().contains("ABC.java")) {
                            jdtLog("Error resolving FieldAccess: $node, error: ${e.message}")
                        }
                    }
                    return true // Continue visiting
                }

                override fun visit(node: MethodInvocation): Boolean {
                    if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker") || file.toString().contains("DefaultInterface.java") || file.toString().contains("ABC.java")) {
                        jdtLog("Visiting MethodInvocation: $node")
                        jdtLog("MethodInvocation expression: ${node.expression}")
                        jdtLog("MethodInvocation expression type: ${node.expression?.javaClass}")
                    }
                    return true // Continue visiting
                }
            })

            if (file.toString().contains("InvokerClass2") || file.toString().contains("desugar/JavaInvoker")) {
                jdtLog("${file.fileName} final reference count: ${result.references.size}")
            }
        } catch (e: Exception) {
            jdtLog("Error processing file $file: ${e.message}")
            result.error = e.toString()
        }
        return result
    }

    private fun handle(b: IBinding, declaredTypes: Set<String>, result: Result) {
        if (b !is IVariableBinding) return
        val vb = b
        if (!vb.isField) return
        val fd = vb.variableDeclaration
        val mod = fd.modifiers
        jdtLog("Found field: ${fd.name}, isStatic=${Modifier.isStatic(mod)}, isFinal=${Modifier.isFinal(mod)}, declaringClass=${fd.declaringClass?.qualifiedName}")
        if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod)) return
        val owner = fd.declaringClass ?: return
        val qname = owner.qualifiedName
        jdtLog("Static final field: ${fd.name}, owner=$qname, declaredTypes=$declaredTypes")

        // Debug self-reference check
        val isSelfReference = qname != null && declaredTypes.contains(qname)
        jdtLog("Self-reference check: qname=$qname, isSelfReference=$isSelfReference")
        if (isSelfReference) {
            jdtLog("Skipping self-reference: $qname.${fd.name}")
            return
        }

        // Filter out Android SDK fields to match JavaParser behavior
        // JavaParser cannot resolve Android SDK classes, so we exclude them from JDT results
        if (qname.startsWith("android.") || qname.contains(".android.")) {
            jdtLog("Skipping Android SDK field: $qname.${fd.name}")
            return
        }

        // Don't filter duplicates - count each distinct usage of a static final field
        // This matches JavaParser behavior where multiple references to the same field are counted
        jdtLog("Adding reference: $qname.${fd.name}")
        result.references.add(Reference(qname, fd.name, owner.getPackage().name))
    }

    private fun handleKnownStaticFinalFields(node: QualifiedName, declaredTypes: Set<String>, result: Result) {
        val fullyQualifiedName = node.toString()

        // Handle known static final fields that JDT can't resolve
        when (fullyQualifiedName) {
            "System.out", "System.err", "System.in" -> {
                val owner = "java.lang.System"
                val field = when (fullyQualifiedName) {
                    "System.out" -> "out"
                    "System.err" -> "err"
                    "System.in" -> "in"
                    else -> return
                }

                // Skip self-references
                if (declaredTypes.contains(owner)) {
                    jdtLog("Skipping self-reference for known field: $owner.$field")
                    return
                }

                // Don't filter duplicates - count each distinct usage
                jdtLog("Adding known static final field: $owner.$field")
                result.references.add(Reference(owner, field, "java.lang"))
            }
            // Handle inner class static final fields that might be self-references
            else -> {
                // Check if this looks like an inner class field reference that might be a self-reference
                val parts = fullyQualifiedName.split(".")
                if (parts.size >= 3) {
                    // Try to construct potential inner class names from declared types
                    for (declaredType in declaredTypes) {
                        if (fullyQualifiedName.startsWith("$declaredType.")) {
                            jdtLog("Skipping potential self-reference: $fullyQualifiedName (declared type: $declaredType)")
                            return
                        }
                    }
                }
            }
        }
    }

    private fun declaredTypeNames(cu: CompilationUnit): Set<String> {
        val names: MutableSet<String> = HashSet()
        cu.accept(object : ASTVisitor() {
            override fun visit(node: TypeDeclaration): Boolean {
                val binding = node.resolveBinding()
                if (binding != null) {
                    names.add(binding.qualifiedName)
                    // Also add inner classes recursively
                    addInnerClasses(node, binding.qualifiedName, names)
                }
                return false
            }

            override fun visit(node: EnumDeclaration): Boolean {
                val binding = node.resolveBinding()
                if (binding != null) {
                    names.add(binding.qualifiedName)
                }
                return false
            }

            override fun visit(node: AnnotationTypeDeclaration): Boolean {
                val binding = node.resolveBinding()
                if (binding != null) {
                    names.add(binding.qualifiedName)
                }
                return false
            }

            private fun addInnerClasses(typeDecl: TypeDeclaration, parentName: String, names: MutableSet<String>) {
                // Find all inner class declarations
                typeDecl.bodyDeclarations().forEach { member ->
                    when (member) {
                        is TypeDeclaration -> {
                            val innerName = "$parentName.${member.name.identifier}"
                            names.add(innerName)
                            // Recursively add nested inner classes
                            addInnerClasses(member, innerName, names)
                        }
                        is EnumDeclaration -> {
                            val innerName = "$parentName.${member.name.identifier}"
                            names.add(innerName)
                        }
                        is AnnotationTypeDeclaration -> {
                            val innerName = "$parentName.${member.name.identifier}"
                            names.add(innerName)
                        }
                    }
                }
            }
        })
        return names
    }

    @Throws(IOException::class)
    private fun listJavaFiles(root: Path): List<Path> {
        Files.walk(root).use { s ->
            return s.filter { p: Path -> p.toString().endsWith(".java") }
                .collect(Collectors.toList())
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