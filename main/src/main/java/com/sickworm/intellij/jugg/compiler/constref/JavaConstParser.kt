package com.sickworm.intellij.jugg.compiler.constref

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr
import com.intellij.openapi.diagnostic.Logger
import java.io.File

class JavaConstParser(
    private val logger: Logger,
) {
    private val parserConfiguration = ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        .setCharacterEncoding(Charsets.UTF_8)
    private val parser = JavaParser(parserConfiguration)

    private val inlineableTypes = setOf(
        "int", "long", "float", "double", "boolean",
        "byte", "short", "char", "String", "java.lang.String",
    )

    fun parseDefinitions(sourceFile: File): List<ConstDefinition> {
        if (sourceFile.extension != "java" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val compilationUnit = parse(sourceFile) ?: return emptyList()
        val packageName = compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")
        val definitions = mutableListOf<ConstDefinition>()
        compilationUnit.types.forEach { typeDeclaration ->
            collectDefinitions(
                typeDeclaration = typeDeclaration,
                packageName = packageName,
                outerClassName = null,
                sourcePath = sourcePath,
                definitions = definitions,
            )
        }
        return definitions
    }

    fun parseReferences(sourceFile: File, definitionIndex: ConstDefinitionIndex): List<ConstReference> {
        if (sourceFile.extension != "java" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val compilationUnit = parse(sourceFile) ?: return emptyList()
        val packageName = compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")
        val importContext = buildImportContext(compilationUnit)
        val references = linkedSetOf<ConstReference>()
        val ownerImportContext = OwnerImportContext(
            explicitClassImports = importContext.explicitClassImports,
            packageAsteriskImports = importContext.packageAsteriskImports,
        )

        compilationUnit.findAll(FieldAccessExpr::class.java).forEach { fieldAccess ->
            val constName = fieldAccess.nameAsString
            if (!definitionIndex.hasConstName(constName)) {
                return@forEach
            }
            val ownerText = fieldAccess.scope.toString().trim()
            val resolvedClasses = resolveOwnerCandidates(
                ownerText = ownerText,
                constName = constName,
                packageName = packageName,
                importContext = ownerImportContext,
                definitionIndex = definitionIndex,
            )
            resolvedClasses.forEach { fqClassName ->
                addReference(sourcePath, fqClassName, constName, definitionIndex, references)
            }
        }

        compilationUnit.findAll(NameExpr::class.java).forEach { nameExpr ->
            val constName = nameExpr.nameAsString
            if (!definitionIndex.hasConstName(constName)) {
                return@forEach
            }
            importContext.staticSingleImports[constName].orEmpty().forEach { fqClassName ->
                addReference(sourcePath, fqClassName, constName, definitionIndex, references)
            }
            importContext.staticAsteriskImports.forEach staticImport@{ fqClassName ->
                if (!definitionIndex.hasDefinition(fqClassName, constName)) {
                    return@staticImport
                }
                addReference(sourcePath, fqClassName, constName, definitionIndex, references)
            }
        }

        return references.toList()
    }

    private fun collectDefinitions(
        typeDeclaration: TypeDeclaration<*>,
        packageName: String,
        outerClassName: String?,
        sourcePath: String,
        definitions: MutableList<ConstDefinition>,
    ) {
        val className = typeDeclaration.nameAsString
        val fullClassName = if (outerClassName == null) {
            className
        } else {
            "$outerClassName.$className"
        }
        val fqClassName = if (packageName.isBlank()) {
            fullClassName
        } else {
            "$packageName.$fullClassName"
        }

        typeDeclaration.members
            .filterIsInstance<FieldDeclaration>()
            .forEach { fieldDeclaration ->
                if (!fieldDeclaration.isFinal || (!fieldDeclaration.isStatic && !isInterfaceField(fieldDeclaration, typeDeclaration))) {
                    return@forEach
                }
                val fieldType = fieldDeclaration.commonType.asString()
                if (!inlineableTypes.contains(fieldType)) {
                    return@forEach
                }
                fieldDeclaration.variables.forEach variableLoop@{ variable ->
                    if (!variable.initializer.isPresent) {
                        return@variableLoop
                    }
                    definitions += ConstDefinition(
                        filePath = sourcePath,
                        packageName = packageName,
                        fqClassName = fqClassName,
                        constName = variable.nameAsString,
                        constType = fieldType,
                        constValue = variable.initializer.get().toString(),
                    )
                }
            }

        typeDeclaration.members
            .filterIsInstance<TypeDeclaration<*>>()
            .forEach { nestedType ->
                collectDefinitions(
                    typeDeclaration = nestedType,
                    packageName = packageName,
                    outerClassName = fullClassName,
                    sourcePath = sourcePath,
                    definitions = definitions,
                )
            }
    }

    private fun isInterfaceField(fieldDeclaration: FieldDeclaration, typeDeclaration: TypeDeclaration<*>): Boolean {
        return typeDeclaration is ClassOrInterfaceDeclaration &&
            typeDeclaration.isInterface &&
            fieldDeclaration.isFinal
    }

    private fun addReference(
        sourcePath: String,
        fqClassName: String,
        constName: String,
        definitionIndex: ConstDefinitionIndex,
        references: MutableSet<ConstReference>,
    ) {
        definitionIndex.findByClassAndConst(fqClassName, constName)
            .filter { it.filePath != sourcePath }
            .forEach { definition ->
                references += ConstReference(
                    refFilePath = sourcePath,
                    defFqClassName = definition.fqClassName,
                    constName = definition.constName,
                )
            }
    }

    private fun parse(sourceFile: File): CompilationUnit? {
        return try {
            val parseResult = parser.parse(sourceFile)
            if (!parseResult.isSuccessful || !parseResult.result.isPresent) {
                logger.debug("JavaConstParser failed to parse ${sourceFile.path}: ${parseResult.problems}")
                null
            } else {
                parseResult.result.get()
            }
        } catch (t: Throwable) {
            logger.debug("JavaConstParser exception on ${sourceFile.path}: ${t.message}")
            null
        }
    }

    private data class JavaImportContext(
        val explicitClassImports: MutableMap<String, String> = mutableMapOf(),
        val packageAsteriskImports: MutableSet<String> = mutableSetOf(),
        val staticSingleImports: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        val staticAsteriskImports: MutableSet<String> = mutableSetOf(),
    )

    private fun buildImportContext(compilationUnit: CompilationUnit): JavaImportContext {
        val context = JavaImportContext()
        compilationUnit.imports.forEach { importDeclaration ->
            val importName = importDeclaration.nameAsString
            if (importDeclaration.isStatic) {
                if (importDeclaration.isAsterisk) {
                    context.staticAsteriskImports += importName
                } else {
                    val className = importName.substringBeforeLast('.', "")
                    val constName = importName.substringAfterLast('.')
                    if (className.isNotBlank() && constName.isNotBlank()) {
                        context.staticSingleImports.getOrPut(constName) { mutableSetOf() } += className
                    }
                }
            } else {
                if (importDeclaration.isAsterisk) {
                    context.packageAsteriskImports += importName
                } else {
                    val simpleName = importName.substringAfterLast('.')
                    context.explicitClassImports[simpleName] = importName
                }
            }
        }
        return context
    }
}
