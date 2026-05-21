package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

/**
 * Language parser facade for const-ref analysis.
 * Serializes access to [KotlinConstParser] because the underlying Kotlin PSI environment is not thread-safe.
 */
class ConstRefAnalyzer(
    logger: Logger,
) {
    private val javaConstParser = JavaConstParser(logger.getInstance("JavaConstParser"))
    private val kotlinConstParser = KotlinConstParser(logger.getInstance("KotlinConstParser"))
    private val parserLock = Any()

    fun analyze(files: Collection<File>, baseDefinitions: Collection<ConstDefinition>): Map<String, FileConstParseResult> {
        return withParserLock {
            val sourceFiles = normalizeSourceFiles(files)
            if (sourceFiles.isEmpty()) {
                return@withParserLock emptyMap()
            }

            val definitionsByFile = parseDefinitions(sourceFiles)
            val definitionIndex = ConstDefinitionIndex(baseDefinitions)
            definitionsByFile.forEach { (filePath, definitions) ->
                definitionIndex.replaceFileDefinitions(filePath, definitions)
            }
            val referencesByFile = parseReferences(sourceFiles, definitionIndex)

            sourceFiles.associate { sourceFile ->
                val filePath = sourceFile.toStdPath()
                filePath to FileConstParseResult(
                    definitions = definitionsByFile[filePath].orEmpty(),
                    references = referencesByFile[filePath].orEmpty(),
                )
            }
        }
    }

    fun parseDefinitions(files: Collection<File>): Map<String, List<ConstDefinition>> {
        return withParserLock {
            val sourceFiles = normalizeSourceFiles(files)
            if (sourceFiles.isEmpty()) {
                return@withParserLock emptyMap()
            }
            sourceFiles.associate { sourceFile ->
                sourceFile.toStdPath() to parseDefinitions(sourceFile)
            }
        }
    }

    fun parseReferences(
        files: Collection<File>,
        definitionIndex: ConstDefinitionLookup,
    ): Map<String, List<ConstReference>> {
        return withParserLock {
            val sourceFiles = normalizeSourceFiles(files)
            if (sourceFiles.isEmpty()) {
                return@withParserLock emptyMap()
            }
            sourceFiles.associate { sourceFile ->
                sourceFile.toStdPath() to parseReferences(sourceFile, definitionIndex)
            }
        }
    }

    fun parseReferenceCandidates(files: Collection<File>): Map<String, List<ConstReferenceCandidate>> {
        return withParserLock {
            val sourceFiles = normalizeSourceFiles(files)
            if (sourceFiles.isEmpty()) {
                return@withParserLock emptyMap()
            }
            sourceFiles.associate { sourceFile ->
                sourceFile.toStdPath() to parseReferenceCandidates(sourceFile)
            }
        }
    }

    fun collectReferenceLookupHints(files: Collection<File>): Map<String, ConstReferenceLookupHints> {
        return withParserLock {
            val sourceFiles = normalizeSourceFiles(files)
            if (sourceFiles.isEmpty()) {
                return@withParserLock emptyMap()
            }
            sourceFiles.associate { sourceFile ->
                sourceFile.toStdPath() to collectReferenceLookupHints(sourceFile)
            }
        }
    }

    fun dispose() {
        synchronized(parserLock) {
            kotlinConstParser.dispose()
        }
    }

    /**
     * Recreates the internal [KotlinCoreEnvironment] to release its accumulated string-intern
     * table. Call after each analysis batch during full scans to bound resident heap growth.
     */
    fun resetEnvironment() {
        synchronized(parserLock) {
            kotlinConstParser.resetEnvironment()
        }
    }

    /** Drops internal PSI and resolve caches in the Kotlin compiler environment. */
    fun dropResolveCaches() {
        synchronized(parserLock) {
            kotlinConstParser.dropResolveCaches()
        }
    }

    /**
     * Collects lookup hints and parses references in a single KtFile parse pass for a .kt file.
     * Falls through to [collectReferenceLookupHints] + [parseReferences] for Java files.
     *
     * @param definitionIndexFactory receives the collected hints and returns the lookup index,
     *        or null if no candidates are found (which skips the reference parse).
     */
    fun collectHintsAndParseReferences(
        sourceFile: File,
        definitionIndexFactory: (ConstReferenceLookupHints) -> ConstDefinitionLookup?,
    ): List<ConstReference> {
        if (!sourceFile.exists() || !isSupportedSourceFile(sourceFile)) {
            return emptyList()
        }
        return withParserLock {
            when (sourceFile.extension) {
                "kt" -> kotlinConstParser.collectHintsAndParseReferences(sourceFile, definitionIndexFactory)
                "java" -> javaConstParser.collectHintsAndParseReferences(sourceFile, definitionIndexFactory)
                else -> emptyList()
            }
        }
    }

    private inline fun <T> withParserLock(block: () -> T): T {
        return synchronized(parserLock, block)
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

    private fun parseReferenceCandidates(sourceFile: File): List<ConstReferenceCandidate> {
        return when (sourceFile.extension) {
            "java" -> javaConstParser.parseReferenceCandidates(sourceFile)
            "kt" -> kotlinConstParser.parseReferenceCandidates(sourceFile)
            else -> emptyList()
        }
    }

    private fun collectReferenceLookupHints(sourceFile: File): ConstReferenceLookupHints {
        val trackingLookup = TrackingDefinitionLookup()
        when (sourceFile.extension) {
            "java" -> javaConstParser.parseReferences(sourceFile, trackingLookup)
            "kt" -> kotlinConstParser.parseReferences(sourceFile, trackingLookup)
        }
        return trackingLookup.buildHints()
    }

    private fun isSupportedSourceFile(file: File): Boolean {
        return file.extension == "java" || file.extension == "kt"
    }

    /**
     * Collects lookup hints by replaying parser traversal without requiring real definitions.
     * The returned hints are used to query DB candidates for db-session lookup mode.
     */
    private class TrackingDefinitionLookup : ConstDefinitionLookup {
        private val constNames = linkedSetOf<String>()
        private val classConstKeys = linkedSetOf<Pair<String, String>>()
        private val packageConstKeys = linkedSetOf<Pair<String, String>>()
        private val simpleClassNames = linkedSetOf<String>()
        private val simpleClassConstKeys = linkedSetOf<Pair<String, String>>()

        override fun hasConstName(constName: String): Boolean {
            val normalizedName = constName.trim()
            if (normalizedName.isNotEmpty()) {
                constNames += normalizedName
            }
            return true
        }

        override fun hasClass(fqClassName: String): Boolean {
            return true
        }

        override fun hasDefinition(fqClassName: String, constName: String): Boolean {
            recordClassConstKey(fqClassName, constName)
            return true
        }

        override fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition> {
            recordClassConstKey(fqClassName, constName)
            return emptyList()
        }

        override fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition> {
            val normalizedPackage = packageName.trim()
            val normalizedName = constName.trim()
            if (normalizedPackage.isNotEmpty() && normalizedName.isNotEmpty()) {
                packageConstKeys += normalizedPackage to normalizedName
            }
            return emptyList()
        }

        override fun findClassBySimpleName(simpleName: String): Set<String> {
            val normalizedSimpleName = simpleName.trim()
            if (normalizedSimpleName.isNotEmpty()) {
                simpleClassNames += normalizedSimpleName
            }
            return emptySet()
        }

        override fun findClassBySimpleNameForConst(simpleName: String, constName: String): Set<String> {
            val normalizedSimpleName = simpleName.trim()
            val normalizedConstName = constName.trim()
            if (normalizedSimpleName.isNotEmpty()) {
                simpleClassNames += normalizedSimpleName
            }
            if (normalizedSimpleName.isNotEmpty() && normalizedConstName.isNotEmpty()) {
                simpleClassConstKeys += normalizedSimpleName to normalizedConstName
            }
            return emptySet()
        }

        fun buildHints(): ConstReferenceLookupHints {
            return ConstReferenceLookupHints(
                constNames = constNames,
                classConstKeys = classConstKeys,
                packageConstKeys = packageConstKeys,
                simpleClassNames = simpleClassNames,
                simpleClassConstKeys = simpleClassConstKeys,
            )
        }

        private fun recordClassConstKey(fqClassName: String, constName: String) {
            val normalizedClass = fqClassName.trim()
            val normalizedName = constName.trim()
            if (normalizedClass.isNotEmpty() && normalizedName.isNotEmpty()) {
                classConstKeys += normalizedClass to normalizedName
            }
        }
    }
}
