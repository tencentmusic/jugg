package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.BaseJuggAptProcessor
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.util.LinkedHashMap

/**
 * KuiklyPageJuggAptProcessor keeps Kuikly page aggregation entry in sync for incremental source compile.
 *
 * It scans changed source files for @Page annotation and appends missing register snippets
 * into triggerRegisterPages method of KuiklyCoreEntry generated sources.
 */
class KuiklyPageJuggAptProcessor : BaseJuggAptProcessor() {

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
            .filter { it.type == CompileFile.Type.Kotlin || it.type == CompileFile.Type.Java }
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

    private fun collectPageRegistrations(allCompileFiles: List<CompileFile>, logger: com.intellij.openapi.diagnostic.Logger): List<PageRegistration> {
        val pageRegistrations = LinkedHashMap<String, PageRegistration>()
        val sourceFiles = allCompileFiles.filter { it.type == CompileFile.Type.Kotlin || it.type == CompileFile.Type.Java }
        for (compileFile in sourceFiles) {
            if (!hasAnnotationToken(compileFile.file, PAGE_ANNOTATION_TOKEN)) {
                continue
            }

            val content = try {
                compileFile.file.readText()
            } catch (throwable: Throwable) {
                logger.warn("Read page source failed: ${compileFile.file}, message=${throwable.message}")
                continue
            }
            val packageName = PACKAGE_REGEX.find(content)?.groupValues?.getOrNull(1).orEmpty()

            for (annotationMatch in PAGE_ANNOTATION_REGEX.findAll(content)) {
                val annotationArgs = annotationMatch.groupValues.getOrNull(1).orEmpty()
                val route = parseRoute(annotationArgs) ?: continue
                val className = findAnnotatedClassName(content, annotationMatch.range.last + 1) ?: continue
                val fqcn = if (packageName.isBlank()) className else "$packageName.$className"
                pageRegistrations["$route#$fqcn"] = PageRegistration(route = route, fqcn = fqcn)
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
        logger: com.intellij.openapi.diagnostic.Logger,
    ): CompileFile? {
        val originalContent = entryFile.file.readText()
        val methodRange = findMethodBodyRange(originalContent, TARGET_METHOD_NAME)
        if (methodRange == null) {
            logger.warn("Target method $TARGET_METHOD_NAME not found in ${entryFile.file}.")
            return null
        }

        val methodBody = originalContent.substring(methodRange.openBraceIndex + 1, methodRange.closeBraceIndex)
        val snippetsToAppend = pageRegistrations.mapNotNull { registration ->
            val routeToken = "\"${registration.route}\""
            val classToken = registration.fqcn
            val isAlreadyRegistered = containsAllTokens(methodBody, listOf(routeToken, classToken))
            if (isAlreadyRegistered) {
                return@mapNotNull null
            }
            return@mapNotNull buildRegisterSnippet(entryFile.type, registration)
        }
        if (snippetsToAppend.isEmpty()) {
            return null
        }

        val updatedContent = appendSnippetsToMethodTail(
            content = originalContent,
            methodName = TARGET_METHOD_NAME,
            snippets = snippetsToAppend,
        ) ?: return null
        if (updatedContent == originalContent) {
            return null
        }

        entryFile.file.writeText(updatedContent)
        logger.info("Rewrite Kuikly entry success: ${entryFile.file.name}, append count=${snippetsToAppend.size}")
        return entryFile
    }

    private fun buildRegisterSnippet(type: CompileFile.Type, registration: PageRegistration): String {
        return if (type == CompileFile.Type.Java) {
            buildJavaRegisterSnippet(registration)
        } else {
            buildKotlinRegisterSnippet(registration)
        }
    }

    private fun buildKotlinRegisterSnippet(registration: PageRegistration): String {
        return """
            BridgeManager.registerPageRouter("${registration.route}") {
                ${registration.fqcn}()
            }
        """.trimIndent()
    }

    private fun buildJavaRegisterSnippet(registration: PageRegistration): String {
        return """
            BridgeManager.registerPageRouter("${registration.route}", new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke() {
                    new ${registration.fqcn}();
                    return kotlin.Unit.INSTANCE;
                }
            });
        """.trimIndent()
    }

    private fun parseRoute(annotationArgs: String): String? {
        if (annotationArgs.isBlank()) {
            return null
        }
        val namedRoute = NAMED_ROUTE_REGEX.find(annotationArgs)?.groupValues?.getOrNull(1)
        if (!namedRoute.isNullOrBlank()) {
            return namedRoute
        }
        return FIRST_STRING_REGEX.find(annotationArgs)?.groupValues?.getOrNull(1)
    }

    private fun findAnnotatedClassName(content: String, searchStart: Int): String? {
        val classMatch = CLASS_REGEX.find(content, searchStart) ?: return null
        return classMatch.groupValues.getOrNull(2)
    }

    private fun isKuiklyCoreEntryFile(fileName: String): Boolean {
        return fileName == "KuiklyCoreEntry.kt" || fileName == "KuiklyCoreEntry.java"
    }

    private companion object {
        private const val PAGE_ANNOTATION_TOKEN = "@Page"
        private const val TARGET_METHOD_NAME = "triggerRegisterPages"

        private val PAGE_ANNOTATION_REGEX = Regex("""@Page\s*(?:\((.*?)\))?""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val PACKAGE_REGEX = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", setOf(RegexOption.MULTILINE))
        private val CLASS_REGEX = Regex("""\b(class|object)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val NAMED_ROUTE_REGEX = Regex("""(?:route|path|value|name)\s*=\s*["']([^"']+)["']""")
        private val FIRST_STRING_REGEX = Regex("""["']([^"']+)["']""")
    }
}
