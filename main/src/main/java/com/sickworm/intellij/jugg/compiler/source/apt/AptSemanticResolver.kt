package com.sickworm.intellij.jugg.compiler.source.apt

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.WildcardType
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.jar.JarFile

/** Resolves annotation constants and types on demand, preferring current source over compiled values. */
class AptSemanticResolver(private val context: ICompileContext, private val module: ModuleInfo,
    private val analysis: AptSourceAnalysis) {
    // Cache misses within this round too; getOrPut would repeat lookups for null values.
    private val classes = mutableMapOf<String, ClassNode?>()
    private val sources = mutableMapOf<String, File?>()
    private val values = mutableMapOf<String, Any>()
    private val resolving = mutableSetOf<String>()
    private val paths by lazy {
        module.buildPathInfo.allClassPath + context.getModuleDependencies(module, CompileTask(emptyList(), context.tempCompileDir, CompileStatusHolder.DEFAULT)).map(::File)
    }

    fun classInfo(name: String): ClassNode? {
        val timingStart = System.nanoTime()
        try {
            analysis.count("resolverClassQueries")
            if (classes.containsKey(name)) {
                analysis.count("resolverClassCacheHits")
                return classes[name]
            }
            val path = name.replace('.', '/') + ".class"
            val bytes = paths.firstNotNullOfOrNull { entry ->
                when {
                    entry.isDirectory -> File(entry, path).takeIf { it.isFile }?.readBytes()
                    entry.isFile && entry.extension == "jar" -> JarFile(entry).use { jar ->
                        analysis.count("resolverJarOpens")
                        jar.getJarEntry(path)?.let { jar.getInputStream(it).use { input -> input.readBytes() } }
                    }
                    else -> null
                }
            } ?: ClassLoader.getSystemResourceAsStream(path)?.use { it.readBytes() }
            return bytes?.let { ClassNode().also { node -> ClassReader(it).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) } }
                .also { classes[name] = it }
        } finally {
            analysis.recordTime("resolverClassLookup", System.nanoTime() - timingStart)
        }
    }

    /** Finds a declaration through round snapshots, named paths or SourceFile, without scanning source roots. */
    fun source(name: String): File? {
        val timingStart = System.nanoTime()
        try {
            analysis.count("resolverSourceQueries")
            if (sources.containsKey(name)) {
                analysis.count("resolverSourceCacheHits")
                return sources[name]
            }
            val top = name.substringBefore('$')
            val roots = (listOf(module) + module.moduleDependencies.mapNotNull { context.modules[it.moduleName] }).flatMap { it.sourceDirs }
            val candidates = roots.flatMap { root ->
                listOf("java", "kt").flatMap { extension ->
                    listOf(File(root, "${top.replace('.', '/')}.$extension"), File(root, "${top.substringAfterLast('.')}.$extension"))
                }
            } + analysis.files.map { it.file }
            val simple = top.substringAfterLast('.')
            val source = candidates.distinct().firstOrNull { file ->
                file.isFile && file.extension in listOf("java", "kt") &&
                    (file.nameWithoutExtension == simple || analysis.text(file).contains(simple)) && matchesSource(file, name)
            } ?: classInfo(name)?.sourceFile?.let { filename ->
                roots.flatMap { root -> listOf(File(root, "${top.substringBeforeLast('.', "").replace('.', '/')}/$filename"), File(root, filename)) }
                    .firstOrNull { it.isFile && matchesSource(it, name) }
            }
            sources[name] = source
            return source
        } finally {
            analysis.recordTime("resolverSourceLookup", System.nanoTime() - timingStart)
        }
    }

    private fun matchesSource(file: File, name: String): Boolean {
        val facts = analysis.facts(file)
        return facts.declarations.any { join(facts.packageName, it.name) == name } ||
            analysis.definitions(file).any { it.fqClassName.replace('$', '.') == name.replace('$', '.') }
    }

    fun qualified(name: String, file: File, owner: String = ""): String {
        val timingStart = System.nanoTime()
        try {
            val clean = name.trim().removeSuffix("?")
            val facts = analysis.facts(file)
            val first = clean.substringBefore('.')
            facts.imports[first]?.let { imported ->
                val importedName = imported + clean.removePrefix(first).replace('.', '$')
                return resolvedType(importedName) ?: importedName
            }
            val local = facts.declarations.firstOrNull { it.name.replace('$', '.') == clean || it.name == clean }
            if (local != null) return join(facts.packageName, local.name)
            BUILTINS[clean]?.let { return it }
            val candidates = linkedSetOf<String>()
            if (clean.contains('.')) candidates += clean
            if (owner.isNotEmpty()) candidates += "$owner\$$clean"
            candidates += join(facts.packageName, clean.replace('.', '$'))
            facts.stars.forEach { candidates += "$it.$clean" }
            candidates += "java.lang.$clean"
            return candidates.firstNotNullOfOrNull(::resolvedType)
                ?: error("Cannot resolve type $name in $file")
        } finally {
            analysis.recordTime("resolverQualified", System.nanoTime() - timingStart)
        }
    }

    private fun resolvedType(name: String): String? {
        if (source(name) != null || classInfo(name) != null) return name
        var boundary = name.lastIndexOf('.')
        while (boundary > 0) {
            val nested = name.substring(0, boundary) + name.substring(boundary).replace('.', '$')
            if (source(nested) != null || classInfo(nested) != null) return nested
            boundary = name.lastIndexOf('.', boundary - 1)
        }
        return null
    }

    fun descriptor(type: String, file: File, owner: String): String {
        val nullable = type.trim().endsWith('?')
        val raw = type.trim().removeSuffix("?")
        if (analysis.facts(file).kotlin && raw.startsWith("Array<")) {
            return "[" + descriptor(raw.removePrefix("Array<").removeSuffix(">"), file, owner)
        }
        PRIMITIVE_ARRAYS[raw]?.let { return it }
        val parsed = StaticJavaParser.parseType(raw)
        return when (parsed) {
            is ArrayType -> "[" + descriptor(parsed.componentType.asString(), file, owner)
            is PrimitiveType -> PRIMITIVES.getValue(parsed.asString())
            is ClassOrInterfaceType -> {
                val name = parsed.nameWithScope
                if (!nullable && name in KOTLIN_PRIMITIVES && analysis.facts(file).kotlin) KOTLIN_PRIMITIVES.getValue(name)
                else "L" + qualified(name, file, owner).replace('.', '/') + ";"
            }
            else -> error("Unsupported field type $type in $file")
        }
    }

    fun genericSignature(type: String, file: File, owner: String): String? {
        if (!type.contains('<')) return null
        val parsed = StaticJavaParser.parseType(type.removeSuffix("?"))
        fun signature(node: com.github.javaparser.ast.type.Type): String = when (node) {
            is ArrayType -> "[" + signature(node.componentType)
            is WildcardType -> when {
                node.extendedType.isPresent -> "+" + signature(node.extendedType.get())
                node.superType.isPresent -> "-" + signature(node.superType.get())
                else -> "*"
            }
            is ClassOrInterfaceType -> "L" + qualified(node.nameWithScope, file, owner).replace('.', '/') +
                node.typeArguments.map { args -> "<" + args.joinToString("") { signature(it) } + ">" }.orElse("") + ";"
            else -> descriptor(node.asString(), file, owner)
        }
        return signature(parsed)
    }

    fun value(expression: String, file: File, owner: String): Any {
        if (expression.startsWith("\"\"\"") && expression.endsWith("\"\"\"")) {
            val text = expression.removePrefix("\"\"\"").removeSuffix("\"\"\"")
            check(!text.contains('$')) { "Unsupported interpolated constant in $file" }
            return text
        }
        return evaluate(StaticJavaParser.parseExpression(expression), file, owner)
    }

    private fun evaluate(expression: Expression, file: File, owner: String): Any = when (expression) {
        is StringLiteralExpr -> expression.asString().also { check(!analysis.facts(file).kotlin || !it.contains('$')) }
        is CharLiteralExpr -> expression.asChar()
        is BooleanLiteralExpr -> expression.value
        is IntegerLiteralExpr -> expression.asNumber().toInt()
        is LongLiteralExpr -> expression.asNumber().toLong()
        is DoubleLiteralExpr -> if (expression.value.endsWith("f", ignoreCase = true)) expression.asDouble().toFloat() else expression.asDouble()
        is EnclosedExpr -> evaluate(expression.inner, file, owner)
        is UnaryExpr -> unary(expression, file, owner)
        is BinaryExpr -> binary(expression, file, owner)
        is CastExpr -> cast(evaluate(expression.expression, file, owner), expression.type.asString())
        is ConditionalExpr -> if (evaluate(expression.condition, file, owner) as Boolean) evaluate(expression.thenExpr, file, owner) else evaluate(expression.elseExpr, file, owner)
        is NameExpr -> reference(expression.nameAsString, file, owner)
        is FieldAccessExpr -> reference(expression.toString(), file, owner)
        else -> error("Unsupported annotation expression $expression in $file")
    }

    private fun unary(expression: UnaryExpr, file: File, owner: String): Any {
        val value = evaluate(expression.expression, file, owner)
        return when (expression.operator) {
            UnaryExpr.Operator.MINUS -> when (value) {
                is Double -> -value
                is Float -> -value
                is Long -> -value
                else -> -number(value).toInt()
            }
            UnaryExpr.Operator.PLUS -> value
            UnaryExpr.Operator.LOGICAL_COMPLEMENT -> !(value as Boolean)
            UnaryExpr.Operator.BITWISE_COMPLEMENT -> if (value is Long) value.inv() else number(value).toInt().inv()
            else -> error("Unsupported constant operation $expression")
        }
    }

    /** Evaluates supported scalar operators while preserving JVM numeric widths. */
    private fun binary(expression: BinaryExpr, file: File, owner: String): Any {
        val left = evaluate(expression.left, file, owner)
        val right = evaluate(expression.right, file, owner)
        val op = expression.operator
        if (op == BinaryExpr.Operator.PLUS && (left is String || right is String)) return "$left$right"
        if (left is Boolean && right is Boolean) return when (op) {
            BinaryExpr.Operator.AND, BinaryExpr.Operator.BINARY_AND -> left && right
            BinaryExpr.Operator.OR, BinaryExpr.Operator.BINARY_OR -> left || right
            BinaryExpr.Operator.XOR -> left xor right
            BinaryExpr.Operator.EQUALS -> left == right
            BinaryExpr.Operator.NOT_EQUALS -> left != right
            else -> error("Unsupported boolean operation $expression")
        }
        if (left is Float || left is Double || right is Float || right is Double) {
            val a = number(left).toDouble()
            val b = number(right).toDouble()
            val result = when (op) {
                BinaryExpr.Operator.PLUS -> a + b
                BinaryExpr.Operator.MINUS -> a - b
                BinaryExpr.Operator.MULTIPLY -> a * b
                BinaryExpr.Operator.DIVIDE -> a / b
                BinaryExpr.Operator.REMAINDER -> a % b
                else -> error("Unsupported floating-point operation $expression")
            }
            return if (left is Double || right is Double) result else result.toFloat()
        }
        val a = number(left).toLong()
        val b = number(right).toLong()
        if (op in SHIFTS && left !is Long) return when (op) {
            BinaryExpr.Operator.LEFT_SHIFT -> a.toInt() shl b.toInt()
            BinaryExpr.Operator.SIGNED_RIGHT_SHIFT -> a.toInt() shr b.toInt()
            else -> a.toInt() ushr b.toInt()
        }
        val result = integral(op, a, b)
        val longResult = if (op in SHIFTS) left is Long else left is Long || right is Long
        return if (longResult || result !is Long) result else result.toInt()
    }

    private fun integral(op: BinaryExpr.Operator, a: Long, b: Long): Any = when (op) {
        BinaryExpr.Operator.PLUS -> a + b
        BinaryExpr.Operator.MINUS -> a - b
        BinaryExpr.Operator.MULTIPLY -> a * b
        BinaryExpr.Operator.DIVIDE -> a / b
        BinaryExpr.Operator.REMAINDER -> a % b
        BinaryExpr.Operator.BINARY_OR -> a or b
        BinaryExpr.Operator.BINARY_AND -> a and b
        BinaryExpr.Operator.XOR -> a xor b
        BinaryExpr.Operator.LEFT_SHIFT -> a shl b.toInt()
        BinaryExpr.Operator.SIGNED_RIGHT_SHIFT -> a shr b.toInt()
        BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT -> a ushr b.toInt()
        BinaryExpr.Operator.EQUALS -> a == b
        BinaryExpr.Operator.NOT_EQUALS -> a != b
        BinaryExpr.Operator.LESS -> a < b
        BinaryExpr.Operator.LESS_EQUALS -> a <= b
        BinaryExpr.Operator.GREATER -> a > b
        BinaryExpr.Operator.GREATER_EQUALS -> a >= b
        else -> error("Unsupported integral constant operation $op")
    }

    private fun cast(value: Any, type: String): Any = when (type) {
        "byte" -> number(value).toByte().toInt()
        "short" -> number(value).toShort().toInt()
        "char" -> number(value).toInt().toChar()
        "int" -> number(value).toInt()
        "long" -> number(value).toLong()
        "float" -> number(value).toFloat()
        "double" -> number(value).toDouble()
        else -> error("Unsupported constant cast $type")
    }

    private fun number(value: Any): Number = if (value is Char) value.code else value as Number

    private fun reference(text: String, file: File, owner: String): Any {
        val facts = analysis.facts(file)
        val name = text.substringAfterLast('.')
        val imported = facts.imports[text]
        if (!text.contains('.') && imported == null) {
            val local = analysis.definitions(file).filter { it.constName == text &&
                (it.fqClassName.replace('$', '.') == owner.replace('$', '.') || it.fqClassName.endsWith("${file.nameWithoutExtension}Kt")) }
            if (local.size == 1) return definitionValue(local.single())
        }
        val candidates = when {
            text.contains('.') -> listOf(qualified(text.substringBeforeLast('.'), file, owner) to name)
            imported != null -> listOf(imported.substringBeforeLast('.') to imported.substringAfterLast('.'))
            else -> listOf(owner to name) + facts.stars.map { it to name }
        }
        return candidates.firstNotNullOfOrNull { (type, field) -> constant(type, field) }
            ?: error("Cannot resolve annotation constant $text in $file")
    }

    private fun constant(owner: String, name: String): Any? {
        val key = "$owner.$name"
        values[key]?.let { return it }
        val source = source(owner)
        if (source != null) {
            val definitions = analysis.definitions(source).filter { it.fqClassName.replace('$', '.') == owner.replace('$', '.') && it.constName == name }
            return definitions.singleOrNull()?.let { definitionValue(it) }
        }
        val field = classInfo(owner)?.fields?.firstOrNull { it.name == name } ?: return null
        val value = field.value ?: return null
        val typed = when (field.desc) {
            "Z" -> (value as Number).toInt() != 0
            "C" -> (value as Number).toInt().toChar()
            else -> value
        }
        values[key] = typed
        return typed
    }

    private fun definitionValue(definition: com.sickworm.intellij.jugg.compiler.constref.ConstDefinition): Any {
        val key = "${definition.fqClassName}.${definition.constName}"
        values[key]?.let { return it }
        check(resolving.add(key)) { "Cyclic annotation constant $key" }
        try {
            val raw = value(definition.constValue ?: error("Missing value of $key"), File(definition.filePath), definition.fqClassName)
            val type = definition.constType.lowercase()
            val result = if (type in PRIMITIVES && type != "boolean") cast(raw, type) else raw
            values[key] = result
            return result
        } finally {
            resolving.remove(key)
        }
    }

    private fun join(pkg: String, name: String): String = if (pkg.isEmpty()) name else "$pkg.$name"

    private companion object {
        val SHIFTS = setOf(BinaryExpr.Operator.LEFT_SHIFT, BinaryExpr.Operator.SIGNED_RIGHT_SHIFT, BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT)
        val PRIMITIVES = mapOf("boolean" to "Z", "byte" to "B", "short" to "S", "char" to "C", "int" to "I", "long" to "J", "float" to "F", "double" to "D")
        val KOTLIN_PRIMITIVES = mapOf("Boolean" to "Z", "Byte" to "B", "Short" to "S", "Char" to "C", "Int" to "I", "Long" to "J", "Float" to "F", "Double" to "D")
        val PRIMITIVE_ARRAYS = KOTLIN_PRIMITIVES.mapKeys { it.key + "Array" }.mapValues { "[" + it.value }
        val BUILTINS = mapOf("String" to "java.lang.String", "Any" to "java.lang.Object", "Int" to "java.lang.Integer", "Long" to "java.lang.Long",
            "Boolean" to "java.lang.Boolean", "Byte" to "java.lang.Byte", "Short" to "java.lang.Short", "Char" to "java.lang.Character",
            "Float" to "java.lang.Float", "Double" to "java.lang.Double", "List" to "java.util.List", "Map" to "java.util.Map", "Set" to "java.util.Set",
            "MutableList" to "java.util.List", "MutableMap" to "java.util.Map", "MutableSet" to "java.util.Set")
    }
}
