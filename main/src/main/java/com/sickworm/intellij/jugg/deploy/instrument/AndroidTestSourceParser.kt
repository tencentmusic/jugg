package com.sickworm.intellij.jugg.deploy.instrument

import java.io.File

/**
 * AndroidTestSourceParser extracts class and @Test method names from a Java/Kotlin test source file.
 */
object AndroidTestSourceParser {

    fun resolve(
        sourceFile: File,
        requestedClass: String?,
        requestedMethod: String?,
    ): AndroidTestSourceSelection {
        val content = sourceFile.readText()
        val packageName = PACKAGE_REGEX.find(content)?.groupValues?.get(1).orEmpty()
        val testClasses = parseTestClasses(content, packageName)

        if (testClasses.isEmpty()) {
            throw AndroidTestSourceParseException(
                "no test class found in sourcePath.\nsourcePath: ${sourceFile.path}"
            )
        }

        val selectedClass = when {
            requestedClass.isNullOrBlank() && testClasses.size == 1 -> testClasses.first()
            requestedClass.isNullOrBlank() -> throw AndroidTestSourceParseException(
                buildString {
                    appendLine("multiple test classes found in sourcePath.")
                    appendLine("Candidates:")
                    testClasses.forEach { appendLine("- ${it.className}") }
                    append("Please rerun with --class <className>.")
                }
            )
            else -> testClasses.find { it.matches(requestedClass) }
                ?: throw AndroidTestSourceParseException(
                    "class is not found in sourcePath.\nclass: $requestedClass"
                )
        }

        if (!requestedMethod.isNullOrBlank() && requestedMethod !in selectedClass.testMethods) {
            throw AndroidTestSourceParseException(
                "method is not found in sourcePath.\nclass: ${selectedClass.className}\nmethod: $requestedMethod"
            )
        }

        return AndroidTestSourceSelection(
            testClass = selectedClass.className,
            testMethod = requestedMethod,
        )
    }

    private fun parseTestClasses(content: String, packageName: String): List<ParsedTestClass> {
        val candidates = CLASS_REGEX.findAll(content)
            .map { it.groupValues[1] to it.range.first }
            .toList()
        return candidates.mapNotNull { (className, startIndex) ->
            val classEnd = findClassEnd(content, startIndex)
            val body = content.substring(startIndex, classEnd)
            val testMethods = TEST_METHOD_REGEX.findAll(body)
                .map { it.groupValues[1] }
                .toList()
            if (testMethods.isEmpty()) {
                null
            } else {
                ParsedTestClass(className.withPackage(packageName), className, testMethods)
            }
        }
    }

    private fun findClassEnd(content: String, classStart: Int): Int {
        val bodyStart = content.indexOf('{', classStart)
        if (bodyStart < 0) {
            return content.length
        }
        var depth = 0
        for (index in bodyStart until content.length) {
            when (content[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return index + 1
                    }
                }
            }
        }
        return content.length
    }

    private fun String.withPackage(packageName: String): String {
        return if (packageName.isBlank() || contains('.')) this else "$packageName.$this"
    }

    private data class ParsedTestClass(
        val className: String,
        val simpleClassName: String,
        val testMethods: List<String>,
    ) {
        fun matches(requestedClass: String): Boolean {
            return requestedClass == className || requestedClass == simpleClassName
        }
    }

    private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([\w.]+)\s*;?""")
    private val CLASS_REGEX = Regex("""\b(?:class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
    private val TEST_METHOD_REGEX = Regex(
        """@(?:(?:org\.junit\.)|(?:org\.junit\.jupiter\.api\.))?Test\b[\s\S]*?\b(?:fun|void)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
    )
}

/** Source-level class/method selection normalized from an androidTest file. */
data class AndroidTestSourceSelection(
    val testClass: String?,
    val testMethod: String?,
)

/** Raised when sourcePath class/method selection is missing or ambiguous. */
class AndroidTestSourceParseException(message: String) : RuntimeException(message)
