package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.io.File

/**
 * Not thread-safe. Callers must serialize access externally.
 * The scheduler enforces this via a shared analysis mutex.
 */
class KotlinConstParser(
    private val logger: Logger,
) {
    private var disposable: Disposable = Disposer.newDisposable()
    private var environment: KotlinCoreEnvironment
    private var psiFactory: KtPsiFactory

    init {
        environment = buildEnvironment(disposable)
        psiFactory = KtPsiFactory(environment.project, false)
    }

    fun parseDefinitions(sourceFile: File): List<ConstDefinition> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val ktFile = parseKtFile(sourceFile) ?: return emptyList()
        val packageName = ktFile.packageFqName.asString()
        val definitions = mutableListOf<ConstDefinition>()

        ktFile.declarations.filterIsInstance<KtProperty>().forEach { property ->
            if (!property.hasModifier(KtTokens.CONST_KEYWORD)) {
                return@forEach
            }
            definitions += ConstDefinition(
                filePath = sourcePath,
                packageName = packageName,
                fqClassName = topLevelClassName(packageName, sourceFile),
                constName = property.name ?: return@forEach,
                constType = property.typeReference?.text ?: inferTypeFromInitializer(property.initializer?.text),
                constValue = property.initializer?.text,
            )
        }

        ktFile.declarations.filterIsInstance<KtClassOrObject>().forEach { classOrObject ->
            collectDefinitionsFromClassOrObject(
                classOrObject = classOrObject,
                packageName = packageName,
                outerClassName = null,
                sourcePath = sourcePath,
                definitions = definitions,
            )
        }
        return definitions
    }

    fun parseReferences(sourceFile: File, definitionIndex: ConstDefinitionLookup): List<ConstReference> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val ktFile = parseKtFile(sourceFile) ?: return emptyList()
        return collectReferencesFromKtFile(ktFile, sourcePath, definitionIndex)
    }

    /**
     * Traverses an already-parsed KtFile to collect const references.
     * Extracted so that [parseReferences] and [collectHintsAndParseReferences] can share the
     * visitor logic without parsing the file a second time.
     */
    private fun collectReferencesFromKtFile(
        ktFile: KtFile,
        sourcePath: String,
        definitionIndex: ConstDefinitionLookup,
    ): List<ConstReference> {
        val packageName = ktFile.packageFqName.asString()
        val importContext = buildImportContext(ktFile, definitionIndex)
        val ownerImportContext = OwnerImportContext(
            explicitClassImports = importContext.explicitClassImports,
            packageAsteriskImports = importContext.packageAsteriskImports,
        )

        val references = linkedSetOf<ConstReference>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                val selector = expression.selectorExpression as? KtNameReferenceExpression
                if (selector != null) {
                    val constName = selector.getReferencedName()
                    if (definitionIndex.hasConstName(constName)) {
                        val ownerText = expression.receiverExpression.text.trim()
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
                }
                super.visitDotQualifiedExpression(expression)
            }

            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                val nameExpression = expression as? KtNameReferenceExpression ?: run {
                    super.visitReferenceExpression(expression)
                    return
                }
                if (nameExpression.getStrictParentOfType<KtImportDirective>() != null) {
                    super.visitReferenceExpression(expression)
                    return
                }
                val parentDot = nameExpression.parent as? KtDotQualifiedExpression
                if (parentDot != null && (parentDot.receiverExpression == nameExpression || parentDot.selectorExpression == nameExpression)) {
                    super.visitReferenceExpression(expression)
                    return
                }
                val referenceName = nameExpression.getReferencedName()
                val importedConstTargets = importContext.explicitConstImports[referenceName].orEmpty()
                val hasDirectConst = definitionIndex.hasConstName(referenceName)
                if (!hasDirectConst && importedConstTargets.isEmpty()) {
                    super.visitReferenceExpression(expression)
                    return
                }

                importedConstTargets.forEach { target ->
                    addReference(sourcePath, target.fqClassName, target.constName, definitionIndex, references)
                }
                if (!hasDirectConst) {
                    super.visitReferenceExpression(expression)
                    return
                }
                importContext.classAsteriskImports.forEach { fqClassName ->
                    if (definitionIndex.hasDefinition(fqClassName, referenceName)) {
                        addReference(sourcePath, fqClassName, referenceName, definitionIndex, references)
                    }
                }
                importContext.packageAsteriskImports.forEach { packageNameFromImport ->
                    definitionIndex.findByPackageAndConst(packageNameFromImport, referenceName)
                        .filter { it.fqClassName.endsWith("Kt") }
                        .forEach { definition ->
                            addReference(sourcePath, definition.fqClassName, referenceName, definitionIndex, references)
                        }
                }
                definitionIndex.findByPackageAndConst(packageName, referenceName)
                    .filter { it.fqClassName.endsWith("Kt") }
                    .forEach { definition ->
                        addReference(sourcePath, definition.fqClassName, referenceName, definitionIndex, references)
                    }

                super.visitReferenceExpression(expression)
            }
        })

        return references.toList()
    }
    fun dispose() {
        Disposer.dispose(disposable)
    }

    /**
     * Disposes the current [KotlinCoreEnvironment] and recreates it from scratch.
     * The environment accumulates an internal string-intern table (identifier strings, PSI
     * provider caches) that grows ~200 KB per parsed file and is only released on dispose.
     * Calling this after each analysis batch caps resident heap to roughly
     * batchSize × 200 KB instead of totalFileCount × 200 KB.
     */
    fun resetEnvironment() {
        Disposer.dispose(disposable)
        disposable = Disposer.newDisposable()
        environment = buildEnvironment(disposable)
        psiFactory = KtPsiFactory(environment.project, false)
    }

    /**
     * Drops internal PSI and resolve caches accumulated by the Kotlin compiler environment.
     * Call this after each analysis batch to prevent unbounded heap growth during full scans.
     * Safe to call at any time; subsequent parses will rebuild caches as needed.
     */
    fun dropResolveCaches() {
        PsiManager.getInstance(environment.project).dropResolveCaches()
    }

    private fun buildEnvironment(disposable: Disposable): KotlinCoreEnvironment {
        val configuration = CompilerConfiguration().also {
            it.put(CommonConfigurationKeys.MODULE_NAME, "jugg-const-ref")
            it.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        return KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    /**
     * Collects lookup hints and parses references in a single KtFile parse pass.
     * Use instead of calling [collectReferenceLookupHints] + [parseReferences] separately
     * to halve the number of KtFile allocations per file during Phase 2.
     *
     * @param definitionIndexFactory called with the collected hints to build the lookup index.
     *        The factory may return null if no candidate definitions are found (hints empty).
     * @return the parsed references, or empty list if no hints or no candidates.
     */
    fun collectHintsAndParseReferences(
        sourceFile: File,
        definitionIndexFactory: (ConstReferenceLookupHints) -> ConstDefinitionLookup?,
    ): List<ConstReference> {
        if (sourceFile.extension != "kt" || !sourceFile.exists()) {
            return emptyList()
        }
        val sourcePath = sourceFile.toStdPath()
        val ktFile = parseKtFile(sourceFile) ?: return emptyList()

        // Collect hints by replaying the visitor with a no-op lookup that tracks accesses.
        val trackingLookup = HintsTrackingLookup()
        collectReferencesFromKtFile(ktFile, sourcePath, trackingLookup)
        val hints = trackingLookup.buildHints()
        if (hints.isEmpty()) {
            return emptyList()
        }

        // Build the real index using caller-supplied candidates.
        val definitionIndex = definitionIndexFactory(hints) ?: return emptyList()

        // Re-use the same KtFile to parse references — no second file read.
        return collectReferencesFromKtFile(ktFile, sourcePath, definitionIndex)
    }

    private fun collectDefinitionsFromClassOrObject(
        classOrObject: KtClassOrObject,
        packageName: String,
        outerClassName: String?,
        sourcePath: String,
        definitions: MutableList<ConstDefinition>,
    ) {
        val name = classOrObject.name ?: return
        val className = if (outerClassName == null) {
            name
        } else {
            "$outerClassName.$name"
        }
        val fqClassName = if (packageName.isBlank()) className else "$packageName.$className"

        classOrObject.declarations.filterIsInstance<KtProperty>().forEach { property ->
            if (!property.hasModifier(KtTokens.CONST_KEYWORD)) {
                return@forEach
            }
            definitions += ConstDefinition(
                filePath = sourcePath,
                packageName = packageName,
                fqClassName = fqClassName,
                constName = property.name ?: return@forEach,
                constType = property.typeReference?.text ?: inferTypeFromInitializer(property.initializer?.text),
                constValue = property.initializer?.text,
            )
        }

        if (classOrObject is KtClass) {
            classOrObject.companionObjects.forEach { companion ->
                companion.declarations.filterIsInstance<KtProperty>().forEach companionProperty@{ property ->
                    if (!property.hasModifier(KtTokens.CONST_KEYWORD)) {
                        return@companionProperty
                    }
                    definitions += ConstDefinition(
                        filePath = sourcePath,
                        packageName = packageName,
                        fqClassName = fqClassName,
                        constName = property.name ?: return@companionProperty,
                        constType = property.typeReference?.text ?: inferTypeFromInitializer(property.initializer?.text),
                        constValue = property.initializer?.text,
                    )
                }
            }
        }

        classOrObject.declarations.filterIsInstance<KtClassOrObject>()
            .filterNot { it is KtObjectDeclaration && it.isCompanion() }
            .forEach { nested ->
                collectDefinitionsFromClassOrObject(
                    classOrObject = nested,
                    packageName = packageName,
                    outerClassName = className,
                    sourcePath = sourcePath,
                    definitions = definitions,
                )
            }
    }

    private fun parseKtFile(sourceFile: File): KtFile? {
        return try {
            psiFactory.createFile(sourceFile.name, sourceFile.readText())
        } catch (t: Throwable) {
            logger.debug("KotlinConstParser failed to parse ${sourceFile.path}: ${t.message}")
            null
        }
    }

    private fun topLevelClassName(packageName: String, sourceFile: File): String {
        val className = "${sourceFile.nameWithoutExtension}Kt"
        return if (packageName.isBlank()) className else "$packageName.$className"
    }

    // Lightweight fallback when type reference is absent. Precision is not required for const-ref matching.
    private fun inferTypeFromInitializer(initializer: String?): String {
        if (initializer == null) {
            return ""
        }
        return when {
            initializer.startsWith("\"") -> "String"
            initializer.endsWith("L") -> "Long"
            initializer.endsWith("f", ignoreCase = true) -> "Float"
            initializer.contains('.') -> "Double"
            initializer == "true" || initializer == "false" -> "Boolean"
            initializer.startsWith('\'') && initializer.endsWith('\'') -> "Char"
            else -> "Int"
        }
    }

    private fun addReference(
        sourcePath: String,
        fqClassName: String,
        constName: String,
        definitionIndex: ConstDefinitionLookup,
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

    private data class KotlinImportContext(
        val explicitClassImports: MutableMap<String, String> = mutableMapOf(),
        val packageAsteriskImports: MutableSet<String> = mutableSetOf(),
        val classAsteriskImports: MutableSet<String> = mutableSetOf(),
        val explicitConstImports: MutableMap<String, MutableSet<ImportedConstTarget>> = mutableMapOf(),
    )

    private data class ImportedConstTarget(
        val fqClassName: String,
        val constName: String,
    )

    private fun buildImportContext(ktFile: KtFile, definitionIndex: ConstDefinitionLookup): KotlinImportContext {
        val context = KotlinImportContext()
        ktFile.importDirectives.forEach { importDirective ->
            val importedFqName = importDirective.importedFqName?.asString() ?: return@forEach
            val aliasName = importDirective.aliasName
            if (importDirective.isAllUnder) {
                if (definitionIndex.hasClass(importedFqName)) {
                    context.classAsteriskImports += importedFqName
                } else {
                    context.packageAsteriskImports += importedFqName
                }
                return@forEach
            }
            val importedName = importedFqName.substringAfterLast('.')
            val bindName = aliasName ?: importedName
            if (definitionIndex.hasClass(importedFqName)) {
                context.explicitClassImports[bindName] = importedFqName
                return@forEach
            }

            val owner = importedFqName.substringBeforeLast('.', "")
            if (owner.isNotBlank() && definitionIndex.hasDefinition(owner, importedName)) {
                context.explicitConstImports.getOrPut(bindName) { mutableSetOf() } += ImportedConstTarget(
                    fqClassName = owner,
                    constName = importedName,
                )
                return@forEach
            }

            if (owner.isNotBlank()) {
                definitionIndex.findByPackageAndConst(owner, importedName).forEach { definition ->
                    context.explicitConstImports.getOrPut(bindName) { mutableSetOf() } += ImportedConstTarget(
                        fqClassName = definition.fqClassName,
                        constName = importedName,
                    )
                }
            }
        }
        return context
    }

    /**
     * A [ConstDefinitionLookup] that records every lookup key without requiring real definitions.
     * Used in [collectHintsAndParseReferences] to drive the visitor pass that collects
     * [ConstReferenceLookupHints] from the parsed KtFile.
     */
    private class HintsTrackingLookup : ConstDefinitionLookup {
        private val constNames = linkedSetOf<String>()
        private val classConstKeys = linkedSetOf<Pair<String, String>>()
        private val packageConstKeys = linkedSetOf<Pair<String, String>>()
        private val simpleClassNames = linkedSetOf<String>()

        override fun hasConstName(constName: String): Boolean {
            val n = constName.trim()
            if (n.isNotEmpty()) constNames += n
            return true
        }

        override fun hasClass(fqClassName: String): Boolean = true

        override fun hasDefinition(fqClassName: String, constName: String): Boolean {
            record(fqClassName, constName)
            return true
        }

        override fun findByClassAndConst(fqClassName: String, constName: String): List<ConstDefinition> {
            record(fqClassName, constName)
            return emptyList()
        }

        override fun findByPackageAndConst(packageName: String, constName: String): List<ConstDefinition> {
            val p = packageName.trim()
            val n = constName.trim()
            if (p.isNotEmpty() && n.isNotEmpty()) packageConstKeys += p to n
            return emptyList()
        }

        override fun findClassBySimpleName(simpleName: String): Set<String> {
            val s = simpleName.trim()
            if (s.isNotEmpty()) simpleClassNames += s
            return emptySet()
        }

        fun buildHints() = ConstReferenceLookupHints(
            constNames = constNames,
            classConstKeys = classConstKeys,
            packageConstKeys = packageConstKeys,
            simpleClassNames = simpleClassNames,
        )

        private fun record(fqClassName: String, constName: String) {
            val c = fqClassName.trim()
            val n = constName.trim()
            if (c.isNotEmpty() && n.isNotEmpty()) classConstKeys += c to n
        }
    }
}
