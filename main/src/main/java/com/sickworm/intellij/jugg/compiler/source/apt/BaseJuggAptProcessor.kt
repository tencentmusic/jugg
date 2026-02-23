package com.sickworm.intellij.jugg.compiler.source.apt

import java.io.File

/**
 * BaseJuggAptProcessor provides shared lightweight text-rewrite helpers for custom APT processors.
 *
 * The helpers intentionally avoid AST/PSI dependency for faster incremental processing:
 * - annotation/token presence detection
 * - target-method body range detection
 * - method-tail snippet insertion with indentation normalization
 */
abstract class BaseJuggAptProcessor : IJuggAptProcessor {

    /**
     * Method body location in source text.
     *
     * @property openBraceIndex index of method opening brace '{'
     * @property closeBraceIndex index of matched closing brace '}'
     * @property closeBraceLineStart line start index where closing brace resides
     * @property methodIndent indentation of method closing-brace line
     */
    protected data class MethodBodyRange(
        val openBraceIndex: Int,
        val closeBraceIndex: Int,
        val closeBraceLineStart: Int,
        val methodIndent: String,
    )

    /**
     * Lightweight token-based annotation check.
     */
    protected fun hasAnnotationToken(file: File, annotationToken: String): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }
        return try {
            file.readText().contains(annotationToken)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether all tokens exist in content.
     */
    protected fun containsAllTokens(content: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) {
            return false
        }
        return tokens.all { token ->
            token.isNotBlank() && content.contains(token)
        }
    }

    /**
     * Finds method body range by method name.
     */
    protected fun findMethodBodyRange(content: String, methodName: String): MethodBodyRange? {
        val methodStart = findMethodStart(content, methodName)
        if (methodStart < 0) {
            return null
        }

        val openBraceIndex = content.indexOf('{', methodStart)
        if (openBraceIndex < 0) {
            return null
        }

        val closeBraceIndex = findMatchingBrace(content, openBraceIndex)
        if (closeBraceIndex < 0) {
            return null
        }

        val closeBraceLineStart = content.lastIndexOf('\n', closeBraceIndex).let {
            if (it < 0) 0 else it + 1
        }
        val methodIndent = content.substring(closeBraceLineStart, closeBraceIndex).takeWhile { it == ' ' || it == '\t' }

        return MethodBodyRange(
            openBraceIndex = openBraceIndex,
            closeBraceIndex = closeBraceIndex,
            closeBraceLineStart = closeBraceLineStart,
            methodIndent = methodIndent,
        )
    }

    /**
     * Appends one or more snippets at the end of target method body.
     *
     * Snippets are normalized then re-indented by method body indentation.
     * Returns null when target method cannot be resolved.
     */
    protected fun appendSnippetsToMethodTail(
        content: String,
        methodName: String,
        snippets: List<String>,
    ): String? {
        val filteredSnippets = snippets.filter { it.isNotBlank() }
        if (filteredSnippets.isEmpty()) {
            return content
        }

        val range = findMethodBodyRange(content, methodName) ?: return null
        val bodyIndent = range.methodIndent + "    "
        val formattedSnippets = filteredSnippets.map { buildIndentedSnippet(bodyIndent, it) }

        val head = content.substring(0, range.closeBraceLineStart)
        val tail = content.substring(range.closeBraceIndex) // starts with "}"

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

    /**
     * Normalizes snippet indentation and applies target method-body indentation.
     */
    protected fun buildIndentedSnippet(baseIndent: String, snippet: String): String {
        val lines = snippet.trim('\n').lines()
        val nonBlankLines = lines.filter { it.isNotBlank() }
        if (nonBlankLines.isEmpty()) {
            return ""
        }
        val minIndent = nonBlankLines.minOf { line ->
            line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        }
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                baseIndent + line.drop(minIndent)
            }
        }
    }

    /**
     * Finds method declaration start by method name using lightweight heuristics for Kotlin/Java syntax.
     */
    private fun findMethodStart(content: String, methodName: String): Int {
        val methodCallRegex = Regex("""\b${Regex.escape(methodName)}\s*\(""")
        val declarationMarkers = listOf(
            "fun ",
            "void ",
            "public ",
            "private ",
            "protected ",
            "internal ",
            "static ",
        )
        methodCallRegex.findAll(content).forEach { match ->
            val lineStart = content.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val linePrefix = content.substring(lineStart, match.range.first)
            if (declarationMarkers.any { linePrefix.contains(it) } || linePrefix.trim().isEmpty()) {
                return match.range.first
            }
        }
        return -1
    }

    /**
     * Finds matching closing brace for opening brace index.
     *
     * The scanner skips comments and string/char literals to avoid obvious false brace matches.
     */
    private fun findMatchingBrace(content: String, openBraceIndex: Int): Int {
        if (openBraceIndex < 0 || openBraceIndex >= content.length || content[openBraceIndex] != '{') {
            return -1
        }

        var depth = 0
        var inLineComment = false
        var inBlockComment = false
        var inDoubleQuote = false
        var inSingleQuote = false
        var escaped = false
        var index = openBraceIndex

        while (index < content.length) {
            val char = content[index]
            val next = if (index + 1 < content.length) content[index + 1] else null

            if (inLineComment) {
                if (char == '\n') {
                    inLineComment = false
                }
                index++
                continue
            }

            if (inBlockComment) {
                if (char == '*' && next == '/') {
                    inBlockComment = false
                    index += 2
                    continue
                }
                index++
                continue
            }

            if (inDoubleQuote) {
                if (!escaped && char == '"') {
                    inDoubleQuote = false
                }
                escaped = !escaped && char == '\\'
                index++
                continue
            }

            if (inSingleQuote) {
                if (!escaped && char == '\'') {
                    inSingleQuote = false
                }
                escaped = !escaped && char == '\\'
                index++
                continue
            }

            if (char == '/' && next == '/') {
                inLineComment = true
                index += 2
                continue
            }
            if (char == '/' && next == '*') {
                inBlockComment = true
                index += 2
                continue
            }

            if (char == '"') {
                inDoubleQuote = true
                escaped = false
                index++
                continue
            }
            if (char == '\'') {
                inSingleQuote = true
                escaped = false
                index++
                continue
            }

            if (char == '{') {
                depth++
            } else if (char == '}') {
                depth--
                if (depth == 0) {
                    return index
                }
            }
            index++
        }
        return -1
    }
}

