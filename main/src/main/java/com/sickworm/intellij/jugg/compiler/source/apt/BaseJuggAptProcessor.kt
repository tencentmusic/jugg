package com.sickworm.intellij.jugg.compiler.source.apt

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.constref.ConstDefinitionIndex
import com.sickworm.intellij.jugg.compiler.constref.ConstRefAnalyzer
import com.sickworm.intellij.jugg.deploy.ClassFileLookupHelper
import com.sickworm.intellij.jugg.deploy.classNameToPath
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * BaseJuggAptProcessor provides shared rewrite helpers for custom APT processors.
 *
 * This base does not do source-text parsing. Processor-specific AST parsing should be done in
 * derived implementations (for example BaseKotlinJuggAptProcessor).
 */
abstract class BaseJuggAptProcessor : IJuggAptProcessor {

    /**
     * Method body location in source text.
     *
     * @property openBraceIndex index of method opening brace '{'
     * @property closeBraceIndex index of matched closing brace '}'
     * @property closeBraceLineStart line start index where closing brace resides
     * @property methodIndent indentation of method closing-brace line
     */
    protected data class MethodBodyRange(
        val openBraceIndex: Int,
        val closeBraceIndex: Int,
        val closeBraceLineStart: Int,
        val methodIndent: String,
    )

    protected data class StringConstReference(
        val fqClassName: String,
        val constName: String,
    )

    /**
     * Resolves string constants from parsed source definitions first, then compiled class files.
     */
    protected class StringConstReferenceResolver(
        private val definitionIndex: ConstDefinitionIndex,
        private val findClassFilesByName: (List<String>) -> List<File>,
        private val logger: Logger,
    ) {
        private val resolvedValueByReference = mutableMapOf<StringConstReference, String?>()
        private val resolvedClassFileByClassName = mutableMapOf<String, File?>()
        private val parsedClassConstValues = mutableMapOf<String, Map<String, String>>()

        fun resolve(referenceCandidates: List<StringConstReference>): String? {
            if (referenceCandidates.isEmpty()) {
                return null
            }
            referenceCandidates.forEach { reference ->
                val resolved = resolve(reference)
                if (resolved != null) {
                    return resolved
                }
            }
            return null
        }

        private fun resolve(reference: StringConstReference): String? {
            return resolvedValueByReference.getOrPut(reference) {
                resolveFromDefinitions(reference) ?: resolveFromClassFile(reference)
            }
        }

        private fun resolveFromDefinitions(reference: StringConstReference): String? {
            val definitions = definitionIndex.findByClassAndConst(reference.fqClassName, reference.constName)
            definitions.forEach { definition ->
                val parsedValue = parseStringLiteralExpression(definition.constValue)
                if (parsedValue != null) {
                    return parsedValue
                }
            }
            return null
        }

        private fun resolveFromClassFile(reference: StringConstReference): String? {
            val classFile = resolvedClassFileByClassName.getOrPut(reference.fqClassName) {
                val classNameCandidates = buildJvmClassNameCandidates(reference.fqClassName)
                val foundClassFiles = findClassFilesByName(classNameCandidates)
                if (foundClassFiles.isEmpty()) {
                    return@getOrPut null
                }
                classNameCandidates.forEach { candidate ->
                    val expectedClassFileName = File(candidate.classNameToPath).name
                    val foundByName = foundClassFiles.firstOrNull { it.name == expectedClassFileName }
                    if (foundByName != null) {
                        return@getOrPut foundByName
                    }
                }
                foundClassFiles.first()
            } ?: return null
            val classConstValues = parsedClassConstValues.getOrPut(classFile.absolutePath) {
                readStringConstValuesFromClassFile(classFile)
            }
            return classConstValues[reference.constName]
        }

        private fun readStringConstValuesFromClassFile(classFile: File): Map<String, String> {
            if (!classFile.exists() || !classFile.isFile) {
                return emptyMap()
            }
            val constValues = linkedMapOf<String, String>()
            return try {
                classFile.inputStream().use { inputStream ->
                    val classReader = ClassReader(inputStream)
                    classReader.accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitField(
                            access: Int,
                            name: String?,
                            descriptor: String?,
                            signature: String?,
                            value: Any?,
                        ) = super.visitField(access, name, descriptor, signature, value).also {
                            val fieldName = name ?: return@also
                            val isStaticFinalField = (access and Opcodes.ACC_STATIC) != 0 && (access and Opcodes.ACC_FINAL) != 0
                            if (isStaticFinalField && descriptor == "Ljava/lang/String;" && value is String) {
                                constValues[fieldName] = value
                            }
                        }
                    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
                constValues
            } catch (t: Throwable) {
                logger.debug("Failed to parse class const values from ${classFile.path}: ${t.message}")
                emptyMap()
            }
        }

        private fun buildJvmClassNameCandidates(fqClassName: String): List<String> {
            val candidates = linkedSetOf<String>()
            var currentName = fqClassName
            candidates += currentName
            var lastDotIndex = currentName.lastIndexOf('.')
            while (lastDotIndex > 0) {
                currentName = currentName.substring(0, lastDotIndex) + "$" + currentName.substring(lastDotIndex + 1)
                candidates += currentName
                lastDotIndex = currentName.lastIndexOf('.')
            }
            return candidates.toList()
        }

        private fun parseStringLiteralExpression(rawExpression: String?): String? {
            val expression = rawExpression?.trim() ?: return null
            if (expression.length >= 6 && expression.startsWith("\"\"\"") && expression.endsWith("\"\"\"")) {
                return expression.substring(3, expression.length - 3)
            }
            if (expression.length >= 2 && expression.startsWith("\"") && expression.endsWith("\"")) {
                return expression.substring(1, expression.length - 1)
            }
            return null
        }
    }

    /**
     * Normalizes snippet indentation and applies target method-body indentation.
     */
    protected fun buildIndentedSnippet(baseIndent: String, snippet: String): String {
        val lines = snippet.trim('\n').lines()
        val nonBlankLines = lines.filter { it.isNotBlank() }
        if (nonBlankLines.isEmpty()) {
            return ""
        }
        val minIndent = nonBlankLines.minOf { line ->
            line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        }
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                baseIndent + line.drop(minIndent)
            }
        }
    }

    protected fun createStringConstReferenceResolver(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
        logger: Logger,
    ): StringConstReferenceResolver {
        val sourceFiles = allCompileFiles
            .asSequence()
            .filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
            .map { it.file }
            .filter { it.exists() && it.isFile }
            .distinctBy { it.absolutePath }
            .toList()
        val definitionIndex = if (sourceFiles.isEmpty()) {
            ConstDefinitionIndex()
        } else {
            val constRefAnalyzer = ConstRefAnalyzer(logger)
            try {
                val definitions = constRefAnalyzer.parseDefinitions(sourceFiles).values.flatten()
                ConstDefinitionIndex(definitions)
            } finally {
                constRefAnalyzer.dispose()
            }
        }
        return StringConstReferenceResolver(
            definitionIndex = definitionIndex,
            findClassFilesByName = { classNames ->
                findClassFilesByName(
                    classNames = classNames,
                    context = context,
                    module = module,
                    logger = logger,
                )
            },
            logger = logger,
        )
    }

    protected fun findClassFilesByName(
        classNames: List<String>,
        context: ICompileContext,
        module: ModuleInfo,
        logger: Logger,
    ): List<File> {
        if (classNames.isEmpty()) {
            return emptyList()
        }
        val helperTempDir = context.tempCompileDir.resolve("apt_class_lookup")
        val dependModules = module.moduleDependencies.mapNotNull { context.modules[it.moduleName] }
        val dependLibraries = module.libraryDependencies.map { it.file }
        val foundInDependencies = ClassFileLookupHelper.findClassFilesByName(
            classNames = classNames,
            dependModules = dependModules,
            dependLibraries = dependLibraries,
            tempDir = helperTempDir,
            logger = logger,
        )
        val remainClassNames = findMissingClassNames(classNames, foundInDependencies)
        if (remainClassNames.isEmpty()) {
            return foundInDependencies.map { it.file }.distinctBy { it.absolutePath }
        }

        val allDependModules = context.modules.values.toList()
        val allDependLibraries = context.modules.values
            .asSequence()
            .flatMap { moduleInfo -> moduleInfo.libraryDependencies.asSequence().map { it.file } }
            .distinctBy { it.absolutePath }
            .toList()
        val foundInAllModules = ClassFileLookupHelper.findClassFilesByName(
            classNames = remainClassNames,
            dependModules = allDependModules,
            dependLibraries = allDependLibraries,
            tempDir = helperTempDir,
            logger = logger,
        )
        return (foundInDependencies + foundInAllModules)
            .map { it.file }
            .distinctBy { it.absolutePath }
    }

    private fun findMissingClassNames(
        classNames: List<String>,
        foundClassFiles: List<ClassFileLookupHelper.ClassFileLookupResult>,
    ): List<String> {
        val foundFileNames = foundClassFiles.map { it.file.name }.toSet()
        return classNames
            .distinct()
            .filter { className ->
                val expectedFileName = File(className.classNameToPath).name
                expectedFileName !in foundFileNames
            }
    }
}
