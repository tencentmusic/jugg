package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ClassPreparation
import com.sickworm.intellij.jugg.compiler.ClassPreparationInput
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.data.ClassAnalysis
import com.sickworm.intellij.jugg.deploy.data.ClassAnalysisBatch
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.zip.ZipFile

/** Transforms analyzed program classes before D8 without reading program inputs again. */
internal class TransformerCompiler(
    private val context: ICompileContext,
    private val logger: Logger,
) {
    private val hiltTransformer = HiltAndroidEntryPointTransformer()

    /** Applies supported bytecode transforms and returns analysis matching the final D8 inputs. */
    fun transform(task: CompileTask, module: ModuleInfo, preparation: ClassPreparation): ClassPreparation {
        val startedAt = System.currentTimeMillis()
        val stats = TransformStats()
        val inputs = preparation.inputs
        val programClasses = inputs
            .filter { it.bytes != null }
            .associateBy { it.analysis.analyses.single().className }
        val transformDir = File(context.tempCompileDir, "transform")
        transformDir.deleteRecursively()
        transformDir.mkdirs()
        val classpathDir = File(context.tempCompileDir, "transform_classpath")
        classpathDir.deleteRecursively()
        classpathDir.mkdirs()
        val classpath = context.getModuleDependencies(module, task)
        val transformedInputs = inputs.map {
            transformInput(it, programClasses, classpath, module, transformDir, classpathDir, stats)
        }
        val requiredClasspathFiles = preparation.requiredClasspathFiles +
                transformedInputs.mapNotNull { it.requiredClasspathFile }
        val totalCost = preparation.analysisCostMillis + System.currentTimeMillis() - startedAt

        logger.trace("[PERF] TransformerCompiler.transform " +
                "classes=${inputs.sumOf { it.analysis.analyses.size }}, bytes=${preparation.parsedByteCount}, " +
                "hilt=${stats.transformedCount}, analysis=${preparation.analysisCostMillis}ms, " +
                "transform=${stats.transformedCost}ms, total=${totalCost}ms")
        return preparation.transformed(
            inputs = transformedInputs.map { it.input },
            requiredClasspathFiles = requiredClasspathFiles,
        )
    }

    private fun transformInput(
        input: ClassPreparationInput,
        programClasses: Map<String, ClassPreparationInput>,
        classpath: List<String>,
        module: ModuleInfo,
        transformDir: File,
        classpathDir: File,
        stats: TransformStats,
    ): TransformedInput {
        val bytes = input.bytes ?: return TransformedInput(input)
        val analysis = input.analysis.analyses.single()
        if (!hiltTransformer.isEntryPoint(analysis)) {
            return TransformedInput(input)
        }

        val generatedClass = resolveGeneratedClass(analysis, programClasses, classpath, module, classpathDir)

        val transformStartedAt = System.currentTimeMillis()
        val result = transform(bytes, analysis, generatedClass.bytes)
        stats.transformedCost += System.currentTimeMillis() - transformStartedAt
        stats.transformedCount++
        val outputFile = writeTransformedClass(input, result.bytes, transformDir)
        return TransformedInput(
            input = input.copy(
                file = input.file.copy(
                    file = outputFile,
                    baseDir = if (outputFile == input.file.file) input.file.baseDir else transformDir,
                ),
                bytes = result.bytes,
                analysis = ClassAnalysisBatch.from(listOf(result.analysis)),
            ),
            requiredClasspathFile = generatedClass.classpathFile,
        )
    }

    private fun resolveGeneratedClass(
        analysis: ClassAnalysis,
        programClasses: Map<String, ClassPreparationInput>,
        classpath: List<String>,
        module: ModuleInfo,
        classpathDir: File,
    ): LocatedClass {
        val generatedSuperclass = hiltTransformer.generatedSuperclass(analysis.className)
        programClasses[generatedSuperclass.toClassSigName()]?.let {
            return LocatedClass(
                requireNotNull(it.bytes),
                null,
            )
        }
        val lookupResult = findClass(generatedSuperclass, classpath, module, classpathDir)
        return lookupResult.locatedClass
            ?: throwGeneratedClassMissing(analysis, generatedSuperclass, lookupResult.firstFailure)
    }

    private fun throwGeneratedClassMissing(
        analysis: ClassAnalysis,
        generatedSuperclass: String,
        lookupFailure: Exception?,
    ): Nothing {
        val failureDetail = lookupFailure?.message?.let { "; classpath read failed: $it" }.orEmpty()
        val message = "pre-D8 Hilt transform failed for ${analysis.className}: generated base " +
            "${generatedSuperclass.replace('/', '.')} not found$failureDetail; run a full Gradle build"
        if (lookupFailure == null) {
            logger.warn(message)
        } else {
            logger.warn(message, lookupFailure)
        }
        throw IllegalStateException(message, lookupFailure)
    }

    private fun transform(
        bytes: ByteArray,
        analysis: ClassAnalysis,
        generatedBaseBytes: ByteArray,
    ): HiltTransformResult {
        return try {
            hiltTransformer.transform(bytes, analysis, generatedBaseBytes)
        } catch (e: Exception) {
            val message = "pre-D8 Hilt transform failed for ${analysis.className}: ${e.message.orEmpty()}"
            logger.warn(message, e)
            throw IllegalStateException(message, e)
        }
    }

    private fun writeTransformedClass(input: ClassPreparationInput, bytes: ByteArray, transformDir: File): File {
        if (bytes === input.bytes) {
            return input.file.file
        }
        return File(transformDir, input.file.relativeFile.path).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun findClass(
        internalName: String,
        classpath: List<String>,
        module: ModuleInfo,
        extractionDir: File,
    ): ClassLookupResult {
        val relativePath = "$internalName.class"
        var firstFailure: Exception? = null
        classpath.forEach { path ->
            val entry = File(path)
            try {
                findClass(entry, relativePath, module, extractionDir)?.let {
                    return ClassLookupResult(it, firstFailure)
                }
            } catch (e: Exception) {
                firstFailure = firstFailure ?: e
                logger.debug("Failed to read Hilt classpath entry $entry, continue lookup", e)
            }
        }
        return ClassLookupResult(null, firstFailure)
    }

    private fun findClass(
        entry: File,
        relativePath: String,
        module: ModuleInfo,
        extractionDir: File,
    ): LocatedClass? {
        if (entry.isDirectory) {
            val classFile = File(entry, relativePath)
            if (!classFile.isFile) {
                return null
            }
            val bytes = classFile.readBytes()
            return LocatedClass(
                bytes,
                CompileFile(CompileFile.Type.Class, classFile, entry, module),
            )
        }
        if (!entry.isFile) {
            return null
        }
        return findClassInArchive(entry, relativePath, module, extractionDir)
    }

    private fun findClassInArchive(
        archive: File,
        relativePath: String,
        module: ModuleInfo,
        extractionDir: File,
    ): LocatedClass? {
        return ZipFile(archive).use { zipFile ->
            val zipEntry = zipFile.getEntry(relativePath) ?: return null
            val bytes = zipFile.getInputStream(zipEntry).use { it.readBytes() }
            val extractedFile = File(extractionDir, relativePath).apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
            LocatedClass(
                bytes,
                CompileFile(CompileFile.Type.Class, extractedFile, extractionDir, module),
            )
        }
    }

    private fun String.toClassSigName(): String = "L$this;"

    private data class TransformedInput(
        val input: ClassPreparationInput,
        val requiredClasspathFile: CompileFile? = null,
    )

    private class LocatedClass(
        val bytes: ByteArray,
        val classpathFile: CompileFile?,
    )

    private data class ClassLookupResult(
        val locatedClass: LocatedClass?,
        val firstFailure: Exception?,
    )

    private data class TransformStats(
        var transformedCount: Int = 0,
        var transformedCost: Long = 0,
    )
}
