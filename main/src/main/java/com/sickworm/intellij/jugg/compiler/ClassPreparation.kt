package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.deploy.data.ClassAnalysisBatch
import com.sickworm.intellij.jugg.deploy.data.ClassFileParser

/** Carries analyzed program inputs through pre-D8 transformation and desugar preparation. */
class ClassPreparation internal constructor(
    internal val inputs: List<ClassPreparationInput>,
    internal val requiredClasspathFiles: List<CompileFile> = emptyList(),
    internal val parsedByteCount: Long = 0,
    internal val analysisCostMillis: Long = 0,
) {
    internal val files = inputs.map { it.file }
    internal val analysis = ClassAnalysisBatch.merge(inputs.map { it.analysis })

    internal fun transformed(
        inputs: List<ClassPreparationInput>,
        requiredClasspathFiles: List<CompileFile>,
    ): ClassPreparation {
        return ClassPreparation(inputs, requiredClasspathFiles, parsedByteCount, analysisCostMillis)
    }

    companion object {
        /** Analyzes class and archive inputs before transformation and desugar preparation. */
        internal fun analyze(files: List<CompileFile>): ClassPreparation {
            val startedAt = System.currentTimeMillis()
            var parsedByteCount = 0L
            val inputs = files.map { file ->
                if (file.file.extension == "class") {
                    val bytes = file.file.readBytes()
                    parsedByteCount += bytes.size
                    ClassPreparationInput(
                        file,
                        bytes,
                        ClassAnalysisBatch.from(listOf(ClassFileParser.analyze(bytes))),
                    )
                } else {
                    val parser = ClassFileParser(listOf(file.file))
                    val input = ClassPreparationInput(file, null, parser.parse())
                    parsedByteCount += parser.parsedByteCount
                    input
                }
            }
            return ClassPreparation(
                inputs = inputs,
                parsedByteCount = parsedByteCount,
                analysisCostMillis = System.currentTimeMillis() - startedAt,
            )
        }
    }
}

internal data class ClassPreparationInput(
    val file: CompileFile,
    val bytes: ByteArray?,
    val analysis: ClassAnalysisBatch,
)
