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
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.io.File
import java.util.LinkedHashMap

/**
 * KuiklyPageJuggAptProcessor keeps Kuikly page aggregation entry in sync for incremental source compile.
 *
 * It scans changed source files for @Page annotation and appends missing register snippets
 * into triggerRegisterPages method of KuiklyCoreEntry generated sources.
 */
class KuiklyPageJuggAptProcessor : BaseKotlinJuggAptProcessor() {

    override val id: String = "kuikly-page-processor"

    private data class PageRegistration(
        val route: String,
        val fqcn: String,
    )

    override fun process(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
    ): List<CompileFile> {
        val logger = context.logger.getInstance("KuiklyPageJuggAptProcessor")

        val entryFile = File(module.buildPathInfo.generatedKspSourcePath, "KuiklyCoreEntry.kt")
        if (!entryFile.exists()) {
            logger.debug("No KuiklyCoreEntry generated file found, skip.")
            return emptyList()
        }

        val pageRegistrations = collectPageRegistrations(context, module, allCompileFiles, logger)
        if (pageRegistrations.isEmpty()) {
            logger.debug("No page registrations found in compile files, skip process.")
            return emptyList()
        } else {
            logger.debug("Found page registrations in compile files: $pageRegistrations")
        }

        try {
            val updatedCompileFile = rewriteEntryFile(entryFile, pageRegistrations, logger)
            if (updatedCompileFile != null) {
                return listOf(CompileFile(
                    type = CompileFile.Type.Kotlin,
                    file = updatedCompileFile,
                    baseDir = entryFile.parentFile,
                    module = module,
                ))
            }
        } catch (throwable: Throwable) {
            logger.debug("Rewrite Kuikly entry failed: $entryFile ", throwable)
            logger.warn("Rewrite Kuikly entry failed for ${entryFile}: ${throwable.message}")
        }
        return emptyList()
    }

    private fun collectPageRegistrations(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
        logger: Logger,
    ): List<PageRegistration> {
        val pageRegistrations = LinkedHashMap<String, PageRegistration>()
        val constResolver = createStringConstReferenceResolver(context, module, allCompileFiles, logger)
        val sourceFiles = allCompileFiles.filter { it.type == CompileFile.Type.Kotlin }
        for (compileFile in sourceFiles) {
            val sourceContent = try {
                compileFile.file.readText()
            } catch (throwable: Throwable) {
                logger.warn("Read page source failed: ${compileFile.file}, message=${throwable.message}")
                null
            } ?: continue
            if (!containsImportText(sourceContent, PAGE_IMPORT_FQCN)) {
                continue
            }
            val ktFile = parseKotlinContent(compileFile.file.name, sourceContent) ?: continue
            collectKotlinPageRegistrations(ktFile, constResolver).forEach { registration ->
                pageRegistrations["${registration.route}#${registration.fqcn}"] = registration
            }
        }
        return pageRegistrations.values.toList()
    }

    /**
     * Rewrites one Kuikly entry file by appending missing registrations to triggerRegisterPages.
     */
    private fun rewriteEntryFile(
        entryFile: File,
        pageRegistrations: List<PageRegistration>,
        logger: Logger,
    ): File? {
        val originalContent = entryFile.readText()

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
            logger.warn("Target method $TARGET_METHOD_NAME not found in $entryFile.")
            return null
        }
        if (updatedContent == originalContent) {
            return null
        }

        entryFile.writeText(updatedContent)
        logger.info("Process Kuikly @Page success, added ${snippetsToAppend.size} entry to ${entryFile.name}.")
        return entryFile
    }

    private fun buildKotlinRegisterSnippet(registration: PageRegistration): String {
        return """
            BridgeManager.registerPageRouter("${registration.route}") {
                ${registration.fqcn}()
            }
        """.trimIndent()
    }

    private fun collectKotlinPageRegistrations(
        ktFile: KtFile,
        constResolver: StringConstReferenceResolver,
    ): List<PageRegistration> {
        val packageName = ktFile.packageFqName.asString()
        val pageRegistrations = mutableListOf<PageRegistration>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                val route = extractPageRoute(
                    ktFile = ktFile,
                    annotationEntries = classOrObject.annotationEntries,
                    constResolver = constResolver,
                ) ?: run {
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

    private fun extractPageRoute(
        ktFile: KtFile,
        annotationEntries: List<KtAnnotationEntry>,
        constResolver: StringConstReferenceResolver,
    ): String? {
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
                return resolveAnnotationStringValue(
                    ktFile = ktFile,
                    expression = valueArgument.getArgumentExpression(),
                    constResolver = constResolver,
                )
            }
        }
        return resolveAnnotationStringValue(
            ktFile = ktFile,
            expression = valueArguments.first().getArgumentExpression(),
            constResolver = constResolver,
        )
    }

    private companion object {
        private const val TARGET_METHOD_NAME = "triggerRegisterPages"
        private const val PAGE_IMPORT_FQCN = "com.tencent.kuikly.core.annotations.Page"
        private val PAGE_ROUTE_PARAM_NAMES = setOf("route", "path", "value", "name")
    }
}
