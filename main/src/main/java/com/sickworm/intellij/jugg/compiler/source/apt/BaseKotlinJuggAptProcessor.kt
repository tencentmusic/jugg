package com.sickworm.intellij.jugg.compiler.source.apt

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.io.File

/**
 * BaseKotlinJuggAptProcessor provides Kotlin AST helpers for APT rewrite processors.
 *
 * It aligns with KotlinConstParser by using KotlinCoreEnvironment + KtPsiFactory, so processors
 * can resolve annotations/method-body offsets from PSI instead of fragile token scans.
 */
abstract class BaseKotlinJuggAptProcessor : BaseJuggAptProcessor() {

    protected data class RegisterPageCall(
        val route: String,
        val pageFqcn: String,
    )

    private data class KotlinImportContext(
        val explicitClassImports: Map<String, String>,
        val explicitConstImports: Map<String, Set<String>>,
        val classAsteriskImports: Set<String>,
        val packageAsteriskImports: Set<String>,
    )

    protected fun parseKotlinFile(sourceFile: File): KtFile? {
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.extension.lowercase() != "kt") {
            return null
        }
        return parseKotlinContent(sourceFile.name, sourceFile.readText())
    }

    protected fun parseKotlinContent(fileName: String, content: String): KtFile? {
        return try {
            psiFactory.createFile(fileName, content)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Finds Kotlin function body range from PSI by function name.
     */
    protected fun findKotlinFunctionBodyRange(content: String, functionName: String): MethodBodyRange? {
        val ktFile = parseKotlinContent("AptGenerated.kt", content) ?: return null
        var targetFunction: KtNamedFunction? = null
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                if (targetFunction == null && function.name == functionName) {
                    targetFunction = function
                    return
                }
                super.visitNamedFunction(function)
            }
        })
        val function = targetFunction ?: return null
        val body = function.bodyBlockExpression ?: return null
        val leftBrace = body.lBrace ?: return null
        val rightBrace = body.rBrace ?: return null

        val openBraceIndex = leftBrace.textRange.startOffset
        val closeBraceIndex = rightBrace.textRange.startOffset
        val closeBraceLineStart = content.lastIndexOf('\n', closeBraceIndex).let { if (it < 0) 0 else it + 1 }
        val methodIndent = content.substring(closeBraceLineStart, closeBraceIndex).takeWhile { it == ' ' || it == '\t' }
        return MethodBodyRange(
            openBraceIndex = openBraceIndex,
            closeBraceIndex = closeBraceIndex,
            closeBraceLineStart = closeBraceLineStart,
            methodIndent = methodIndent,
        )
    }

    /**
     * Collects `registerPageRouter` calls in the target Kotlin function by PSI.
     */
    protected fun collectRegisterPageCalls(content: String, functionName: String): List<RegisterPageCall> {
        val ktFile = parseKotlinContent("AptGenerated.kt", content) ?: return emptyList()
        val targetFunction = findNamedFunction(ktFile, functionName) ?: return emptyList()
        val body = targetFunction.bodyBlockExpression ?: return emptyList()

        val calls = mutableListOf<RegisterPageCall>()
        body.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val calleeText = expression.calleeExpression?.text.orEmpty()
                if (calleeText.endsWith("registerPageRouter")) {
                    val route = parseStringLiteral(expression.valueArguments.firstOrNull()?.getArgumentExpression())
                    val pageFqcn = extractLambdaConstructedClassName(expression)
                    if (!route.isNullOrBlank() && !pageFqcn.isNullOrBlank()) {
                        calls += RegisterPageCall(route = route, pageFqcn = pageFqcn)
                    }
                }
                super.visitCallExpression(expression)
            }
        })
        return calls
    }

    /**
     * Appends snippets to Kotlin function tail by resolving function body via PSI.
     */
    protected fun appendSnippetsToMethodTail(
        content: String,
        functionName: String,
        snippets: List<String>,
    ): String? {
        val range = findKotlinFunctionBodyRange(content, functionName) ?: return null
        val filteredSnippets = snippets.filter { it.isNotBlank() }
        if (filteredSnippets.isEmpty()) {
            return content
        }
        val bodyIndent = range.methodIndent + "    "
        val formattedSnippets = filteredSnippets.map { buildIndentedSnippet(bodyIndent, it) }

        val head = content.substring(0, range.closeBraceLineStart)
        val tail = content.substring(range.closeBraceIndex)
        val builder = StringBuilder(head)
        if (!head.endsWith('\n')) {
            builder.append('\n')
        }
        builder.append(formattedSnippets.joinToString("\n\n"))
        if (!builder.endsWith('\n')) {
            builder.append('\n')
        }
        builder.append(range.methodIndent)
        builder.append(tail)
        return builder.toString()
    }

    protected fun containsImportText(sourceContent: String, fqImport: String): Boolean {
        return sourceContent.contains("import $fqImport")
    }

    protected fun resolveAnnotationStringValue(
        ktFile: KtFile,
        expression: KtExpression?,
        constResolver: StringConstReferenceResolver?,
    ): String? {
        parseStringLiteral(expression)?.let { return it }
        if (constResolver == null) {
            return null
        }
        val referenceCandidates = resolveStringConstReferenceCandidates(ktFile, expression)
        if (referenceCandidates.isEmpty()) {
            return null
        }
        return constResolver.resolve(referenceCandidates)
    }

    protected fun resolveStringConstReferenceCandidates(
        ktFile: KtFile,
        expression: KtExpression?,
    ): List<StringConstReference> {
        val unwrappedExpression = unwrapExpression(expression) ?: return emptyList()
        val importContext = buildImportContext(ktFile)
        val packageName = ktFile.packageFqName.asString()
        val references = linkedSetOf<StringConstReference>()

        when (unwrappedExpression) {
            is KtDotQualifiedExpression -> {
                val constName = (unwrappedExpression.selectorExpression as? KtNameReferenceExpression)
                    ?.getReferencedName()
                    ?: return emptyList()
                val ownerText = unwrappedExpression.receiverExpression.text.trim()
                resolveOwnerCandidates(ownerText, packageName, importContext).forEach { ownerClassName ->
                    references += StringConstReference(
                        fqClassName = ownerClassName,
                        constName = constName,
                    )
                }
            }

            is KtNameReferenceExpression -> {
                val constName = unwrappedExpression.getReferencedName()
                importContext.explicitConstImports[constName].orEmpty().forEach { ownerClassName ->
                    references += StringConstReference(
                        fqClassName = ownerClassName,
                        constName = constName,
                    )
                }
                importContext.classAsteriskImports.forEach { ownerClassName ->
                    references += StringConstReference(
                        fqClassName = ownerClassName,
                        constName = constName,
                    )
                }
            }
        }
        return references.toList()
    }

    private fun findNamedFunction(ktFile: KtFile, functionName: String): KtNamedFunction? {
        var targetFunction: KtNamedFunction? = null
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                if (targetFunction == null && function.name == functionName) {
                    targetFunction = function
                    return
                }
                super.visitNamedFunction(function)
            }
        })
        return targetFunction
    }

    private fun extractLambdaConstructedClassName(callExpression: KtCallExpression): String? {
        val lambdaExpression = callExpression.lambdaArguments.firstOrNull()
            ?.getLambdaExpression()
            ?: callExpression.valueArguments.lastOrNull()
                ?.getArgumentExpression() as? KtLambdaExpression
            ?: return null
        val firstStatement = lambdaExpression.bodyExpression
            ?.statements
            ?.firstOrNull()
            ?: return null
        val statementText = firstStatement.text.trim()
        if (statementText.isBlank()) {
            return null
        }
        val statementCall = firstStatement as? KtCallExpression
        val calleeText = statementCall?.calleeExpression?.text?.trim()
        if (!calleeText.isNullOrBlank()) {
            return calleeText
        }
        return Regex("""([A-Za-z_][A-Za-z0-9_\.]*)\s*\(""")
            .find(statementText)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    protected fun parseStringLiteral(expression: KtExpression?): String? {
        val templateExpression = expression as? KtStringTemplateExpression ?: return null
        if (templateExpression.hasInterpolation()) {
            return null
        }
        return templateExpression.entries.joinToString(separator = "") { it.text }.takeIf { it.isNotBlank() }
    }

    private fun buildImportContext(ktFile: KtFile): KotlinImportContext {
        val explicitClassImports = linkedMapOf<String, String>()
        val explicitConstImports = linkedMapOf<String, MutableSet<String>>()
        val classAsteriskImports = linkedSetOf<String>()
        val packageAsteriskImports = linkedSetOf<String>()

        ktFile.importDirectives.forEach { importDirective ->
            val importPath = importDirective.importPath?.pathStr ?: return@forEach
            val aliasName = importDirective.aliasName
            if (importDirective.isAllUnder) {
                registerAsteriskImport(
                    importPath = importPath,
                    classAsteriskImports = classAsteriskImports,
                    packageAsteriskImports = packageAsteriskImports,
                )
                return@forEach
            }

            val importedSimpleName = importPath.substringAfterLast('.')
            val importedName = aliasName ?: importedSimpleName
            val ownerPath = importPath.substringBeforeLast('.', "")
            val ownerSimpleName = ownerPath.substringAfterLast('.')

            if (ownerPath.isNotBlank() && isLikelyTypeName(ownerSimpleName) && !isLikelyTypeName(importedSimpleName)) {
                explicitConstImports.getOrPut(importedName) { linkedSetOf() } += ownerPath
            } else {
                explicitClassImports[importedName] = importPath
            }
        }

        return KotlinImportContext(
            explicitClassImports = explicitClassImports,
            explicitConstImports = explicitConstImports,
            classAsteriskImports = classAsteriskImports,
            packageAsteriskImports = packageAsteriskImports,
        )
    }

    private fun registerAsteriskImport(
        importPath: String,
        classAsteriskImports: MutableSet<String>,
        packageAsteriskImports: MutableSet<String>,
    ) {
        val tailName = importPath.substringAfterLast('.')
        if (isLikelyTypeName(tailName)) {
            classAsteriskImports += importPath
        } else {
            packageAsteriskImports += importPath
        }
    }

    private fun resolveOwnerCandidates(
        ownerText: String,
        packageName: String,
        importContext: KotlinImportContext,
    ): Set<String> {
        if (!ownerTextRegex.matches(ownerText) || ownerText == "this" || ownerText == "super") {
            return emptySet()
        }

        val candidates = linkedSetOf<String>()
        val firstSegment = ownerText.substringBefore('.')
        val explicitImport = importContext.explicitClassImports[firstSegment]
        if (explicitImport != null) {
            val suffix = ownerText.removePrefix(firstSegment)
            candidates += explicitImport + suffix
        }

        if (ownerText.contains('.')) {
            candidates += ownerText
        } else {
            importContext.explicitClassImports[ownerText]?.let { candidates += it }
        }

        if (packageName.isNotBlank()) {
            candidates += "$packageName.$ownerText"
        }
        importContext.packageAsteriskImports.forEach { importPackage ->
            candidates += "$importPackage.$ownerText"
        }
        return candidates
    }

    private fun unwrapExpression(expression: KtExpression?): KtExpression? {
        var current = expression ?: return null
        while (current is KtParenthesizedExpression) {
            current = current.expression ?: return null
        }
        return current
    }

    private fun isLikelyTypeName(name: String): Boolean {
        return name.firstOrNull()?.isUpperCase() == true
    }

    private companion object {
        private val ownerTextRegex = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")
        private val disposable: Disposable = Disposer.newDisposable()
        private val psiFactory: KtPsiFactory by lazy {
            val configuration = CompilerConfiguration().also {
                it.put(CommonConfigurationKeys.MODULE_NAME, "jugg-apt-kotlin-processor")
                it.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            }
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            KtPsiFactory(environment.project, false)
        }
    }
}
