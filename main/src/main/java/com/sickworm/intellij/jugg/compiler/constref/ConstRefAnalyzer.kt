package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

class ConstRefAnalyzer(
    logger: Logger,
) {
    private val javaConstParser = JavaConstParser(logger.getInstance("JavaConstParser"))
    private val kotlinConstParser = KotlinConstParser(logger.getInstance("KotlinConstParser"))

    fun analyze(files: Collection<File>, baseDefinitions: Collection<ConstDefinition>): Map<String, FileConstParseResult> {
        val sourceFiles = normalizeSourceFiles(files)
        if (sourceFiles.isEmpty()) {
            return emptyMap()
        }

        val definitionsByFile = parseDefinitions(sourceFiles)
        val definitionIndex = ConstDefinitionIndex(baseDefinitions)
        definitionsByFile.forEach { (filePath, definitions) ->
            definitionIndex.replaceFileDefinitions(filePath, definitions)
        }
        val referencesByFile = parseReferences(sourceFiles, definitionIndex)

        return sourceFiles.associate { sourceFile ->
            val filePath = sourceFile.toStdPath()
            filePath to FileConstParseResult(
                definitions = definitionsByFile[filePath].orEmpty(),
                references = referencesByFile[filePath].orEmpty(),
            )
        }
    }

    fun parseDefinitions(files: Collection<File>): Map<String, List<ConstDefinition>> {
        val sourceFiles = normalizeSourceFiles(files)
        if (sourceFiles.isEmpty()) {
            return emptyMap()
        }
        return sourceFiles.associate { sourceFile ->
            sourceFile.toStdPath() to parseDefinitions(sourceFile)
        }
    }

    fun parseReferences(
        files: Collection<File>,
        definitionIndex: ConstDefinitionLookup,
    ): Map<String, List<ConstReference>> {
        val sourceFiles = normalizeSourceFiles(files)
        if (sourceFiles.isEmpty()) {
            return emptyMap()
        }
        return sourceFiles.associate { sourceFile ->
            sourceFile.toStdPath() to parseReferences(sourceFile, definitionIndex)
        }
    }

    fun dispose() {
        kotlinConstParser.dispose()
    }

    private fun normalizeSourceFiles(files: Collection<File>): List<File> {
        val sourceFiles = files
            .asSequence()
            .filter { it.exists() && isSupportedSourceFile(it) }
            .distinctBy { it.toStdPath() }
            .toList()
        return sourceFiles
    }

    private fun parseDefinitions(sourceFile: File): List<ConstDefinition> {
        return when (sourceFile.extension) {
            "java" -> javaConstParser.parseDefinitions(sourceFile)
            "kt" -> kotlinConstParser.parseDefinitions(sourceFile)
            else -> emptyList()
        }
    }

    private fun parseReferences(sourceFile: File, definitionIndex: ConstDefinitionLookup): List<ConstReference> {
        return when (sourceFile.extension) {
            "java" -> javaConstParser.parseReferences(sourceFile, definitionIndex)
            "kt" -> kotlinConstParser.parseReferences(sourceFile, definitionIndex)
            else -> emptyList()
        }
    }

    private fun isSupportedSourceFile(file: File): Boolean {
        return file.extension == "java" || file.extension == "kt"
    }
}
