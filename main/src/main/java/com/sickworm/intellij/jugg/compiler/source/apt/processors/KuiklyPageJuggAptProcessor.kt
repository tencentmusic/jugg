package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.BaseKotlinJuggAptProcessor
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.util.LinkedHashMap

/**
 * KuiklyPageJuggAptProcessor keeps Kuikly page aggregation entry in sync for incremental source compile.
 *
 * It scans changed source files for @Page annotation and appends missing register snippets
 * into triggerRegisterPages method of KuiklyCoreEntry generated sources.
 */
class KuiklyPageJuggAptProcessor : BaseKotlinJuggAptProcessor() {

    override val id: String = "kuikly-page-jugg-apt-processor"

    private data class PageRegistration(
        val route: String,
        val fqcn: String,
    )

    override fun process(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
        generatedAptFiles: List<CompileFile>,
    ): List<CompileFile> {
        val logger = context.logger.getInstance("KuiklyPageJuggAptProcessor")
        val pageRegistrations = collectPageRegistrations(allCompileFiles, logger)
        if (pageRegistrations.isEmpty()) {
            return emptyList()
        }

        val entryFiles = generatedAptFiles
            .filter { it.type == CompileFile.Type.Kotlin }
            .filter { isKuiklyCoreEntryFile(it.file.name) }
            .distinctBy { it.file.absolutePath }
        if (entryFiles.isEmpty()) {
            logger.debug("No KuiklyCoreEntry generated file found, skip.")
            return emptyList()
        }

        val rewrittenFiles = mutableListOf<CompileFile>()
        entryFiles.forEach { entryFile ->
            try {
                val updatedCompileFile = rewriteEntryFile(entryFile, pageRegistrations, logger)
                if (updatedCompileFile != null) {
                    rewrittenFiles.add(updatedCompileFile)
                }
            } catch (throwable: Throwable) {
                logger.warn("Rewrite Kuikly entry failed for ${entryFile.file}: ${throwable.message}")
            }
        }
        return rewrittenFiles
    }

    private fun collectPageRegistrations(allCompileFiles: List<CompileFile>, logger: Logger): List<PageRegistration> {
        val pageRegistrations = LinkedHashMap<String, PageRegistration>()
        val sourceFiles = allCompileFiles.filter { it.type == CompileFile.Type.Kotlin }
        for (compileFile in sourceFiles) {
            val ktFile = try {
                parseKotlinFile(compileFile.file)
            } catch (throwable: Throwable) {
                logger.warn("Read page source failed: ${compileFile.file}, message=${throwable.message}")
                null
            } ?: continue
            collectKotlinPageRegistrations(ktFile).forEach { registration ->
                pageRegistrations["${registration.route}#${registration.fqcn}"] = registration
            }
        }
        return pageRegistrations.values.toList()
    }

    /**
     * Rewrites one Kuikly entry file by appending missing registrations to triggerRegisterPages.
     */
    private fun rewriteEntryFile(
        entryFile: CompileFile,
        pageRegistrations: List<PageRegistration>,
        logger: Logger,
    ): CompileFile? {
        val originalContent = entryFile.file.readText()

        val existingKotlinRegistrations = collectRegisterPageCalls(originalContent, TARGET_METHOD_NAME)
            .map { "${it.route}#${it.pageFqcn}" }
            .toSet()
        val snippetsToAppend = pageRegistrations.mapNotNull { registration ->
            val isAlreadyRegistered = existingKotlinRegistrations.contains("${registration.route}#${registration.fqcn}")
            if (isAlreadyRegistered) {
                return@mapNotNull null
            }
            return@mapNotNull buildKotlinRegisterSnippet(registration)
        }
        if (snippetsToAppend.isEmpty()) {
            return null
        }

        val updatedContent = appendSnippetsToMethodTail(
            content = originalContent,
            functionName = TARGET_METHOD_NAME,
            snippets = snippetsToAppend,
        ) ?: run {
            logger.warn("Target method $TARGET_METHOD_NAME not found in ${entryFile.file}.")
            return null
        }
        if (updatedContent == originalContent) {
            return null
        }

        entryFile.file.writeText(updatedContent)
        logger.info("Rewrite Kuikly entry success: ${entryFile.file.name}, append count=${snippetsToAppend.size}")
        return entryFile
    }

    private fun buildKotlinRegisterSnippet(registration: PageRegistration): String {
        return """
            BridgeManager.registerPageRouter("${registration.route}") {
                ${registration.fqcn}()
            }
        """.trimIndent()
    }

    private fun collectKotlinPageRegistrations(ktFile: KtFile): List<PageRegistration> {
        val packageName = ktFile.packageFqName.asString()
        val pageRegistrations = mutableListOf<PageRegistration>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                val route = extractPageRoute(classOrObject.annotationEntries) ?: run {
                    super.visitClassOrObject(classOrObject)
                    return
                }
                val className = resolveRelativeClassName(classOrObject) ?: run {
                    super.visitClassOrObject(classOrObject)
                    return
                }
                val fqcn = if (packageName.isBlank()) className else "$packageName.$className"
                pageRegistrations += PageRegistration(route = route, fqcn = fqcn)
                super.visitClassOrObject(classOrObject)
            }
        })
        return pageRegistrations
    }

    private fun resolveRelativeClassName(classOrObject: KtClassOrObject): String? {
        val selfName = classOrObject.name ?: return null
        val parentName = classOrObject.getStrictParentOfType<KtClassOrObject>()?.let { resolveRelativeClassName(it) }
        return if (parentName.isNullOrBlank()) selfName else "$parentName.$selfName"
    }

    private fun extractPageRoute(annotationEntries: List<KtAnnotationEntry>): String? {
        val pageAnnotation = annotationEntries.firstOrNull { annotation ->
            val shortName = annotation.shortName?.asString()
            shortName == "Page"
        } ?: return null
        val valueArguments = pageAnnotation.valueArguments
        if (valueArguments.isEmpty()) {
            return null
        }
        valueArguments.forEach { valueArgument ->
            val argumentName = valueArgument.getArgumentName()?.asName?.asString()
            if (argumentName in PAGE_ROUTE_PARAM_NAMES) {
                return parseStringLiteral(valueArgument.getArgumentExpression())
            }
        }
        return parseStringLiteral(valueArguments.first().getArgumentExpression())
    }

    private fun isKuiklyCoreEntryFile(fileName: String): Boolean {
        return fileName == "KuiklyCoreEntry.kt"
    }

    private fun parseStringLiteral(expression: KtExpression?): String? {
        val templateExpression = expression as? KtStringTemplateExpression ?: return null
        if (templateExpression.hasInterpolation()) {
            return null
        }
        return templateExpression.entries.joinToString(separator = "") { it.text }.takeIf { it.isNotBlank() }
    }

    private companion object {
        private const val TARGET_METHOD_NAME = "triggerRegisterPages"
        private val PAGE_ROUTE_PARAM_NAMES = setOf("route", "path", "value", "name")
    }
}
