package com.sickworm.intellij.jugg.compiler.source.apt

/**
 * BaseJuggAptProcessor provides shared rewrite helpers for custom APT processors.
 *
 * This base does not do source-text parsing. Processor-specific AST parsing should be done in
 * derived implementations (for example BaseKotlinJuggAptProcessor).
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
}
