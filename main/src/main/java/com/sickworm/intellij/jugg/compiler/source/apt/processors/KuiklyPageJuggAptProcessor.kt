package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.BaseKotlinJuggAptProcessor
import com.sickworm.intellij.jugg.compiler.source.apt.AptSourceAnalysis
import com.sickworm.intellij.jugg.compiler.source.apt.AptSemanticResolver
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.info.ModuleInfo
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

    override fun process(context: ICompileContext, module: ModuleInfo, allCompileFiles: List<CompileFile>,
        analysis: AptSourceAnalysis): List<CompileFile> {
        val logger = context.logger.getInstance("KuiklyPageJuggAptProcessor")

        val entryFile = File(module.buildPathInfo.generatedKspSourcePath, "KuiklyCoreEntry.kt")
        if (!entryFile.exists()) {
            logger.debug("No KuiklyCoreEntry generated file found, skip.")
            return emptyList()
        }

        val pageRegistrations = collectPageRegistrations(context, module, analysis, logger)
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

    private fun collectPageRegistrations(context: ICompileContext, module: ModuleInfo,
        analysis: AptSourceAnalysis, logger: Logger): List<PageRegistration> {
        val registrations = LinkedHashMap<String, PageRegistration>()
        val resolver by lazy { AptSemanticResolver(context, module, analysis) }
        analysis.files.filter { it.type == CompileFile.Type.Kotlin }.forEach { input ->
            try {
                if (!analysis.mentions(input.file, PAGE_IMPORT_FQCN)) return@forEach
                val facts = analysis.facts(input.file)
                facts.declarations.forEach declaration@{ type ->
                    val annotation = type.annotations.firstOrNull {
                        (facts.imports[it.name] ?: it.name) == PAGE_IMPORT_FQCN
                    } ?: return@declaration
                    val expression = annotation.arguments.entries.firstOrNull { it.key in PAGE_ROUTE_PARAM_NAMES }?.value
                        ?: annotation.arguments["#0"] ?: return@declaration
                    val fqcn = if (facts.packageName.isEmpty()) type.name else "${facts.packageName}.${type.name}"
                    val route = resolver.value(expression, input.file, fqcn) as? String
                        ?: error("Page route is not a string in ${input.file}")
                    if (route.isNotBlank()) {
                        val registration = PageRegistration(route, fqcn.replace('$', '.'))
                        registrations["${registration.route}#${registration.fqcn}"] = registration
                    }
                }
            } catch (failure: Throwable) {
                logger.warn("Kuikly Page detection failed for ${module.name}, file=${input.file}; " +
                        "generation skipped for this input: ${failure.message}", failure)
            }
        }
        return registrations.values.toList()
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

    private companion object {
        private const val TARGET_METHOD_NAME = "triggerRegisterPages"
        private const val PAGE_IMPORT_FQCN = "com.tencent.kuikly.core.annotations.Page"
        private val PAGE_ROUTE_PARAM_NAMES = setOf("route", "path", "value", "name")
    }
}
