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
        val sourceFiles = files
            .asSequence()
            .filter { it.exists() && isSupportedSourceFile(it) }
            .distinctBy { it.toStdPath() }
            .toList()
        if (sourceFiles.isEmpty()) {
            return emptyMap()
        }

        val definitionsByFile = mutableMapOf<String, List<ConstDefinition>>()
        sourceFiles.forEach { sourceFile ->
            val filePath = sourceFile.toStdPath()
            definitionsByFile[filePath] = parseDefinitions(sourceFile)
        }

        val allDefinitions = baseDefinitions + definitionsByFile.values.flatten()
        val definitionIndex = ConstDefinitionIndex(allDefinitions)

        return sourceFiles.associate { sourceFile ->
            val filePath = sourceFile.toStdPath()
            val definitions = definitionsByFile[filePath].orEmpty()
            val references = parseReferences(sourceFile, definitionIndex)
            filePath to FileConstParseResult(definitions, references)
        }
    }

    fun dispose() {
        kotlinConstParser.dispose()
    }

    private fun parseDefinitions(sourceFile: File): List<ConstDefinition> {
        return when (sourceFile.extension) {
            "java" -> javaConstParser.parseDefinitions(sourceFile)
            "kt" -> kotlinConstParser.parseDefinitions(sourceFile)
            else -> emptyList()
        }
    }

    private fun parseReferences(sourceFile: File, definitionIndex: ConstDefinitionIndex): List<ConstReference> {
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
