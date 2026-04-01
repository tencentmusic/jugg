package com.sickworm.intellij.jugg.compiler.obfuscation

import java.io.File
import java.nio.file.Path

/**
 * Reader for R8/ProGuard usage.txt.
 *
 * It tracks classes and members removed from the final APK so incremental
 * bridge generation can avoid reviving deleted members.
 */
class R8UsageReader private constructor(
    private val removedClassUsageMap: Map<String, RemovedClassUsage>
) {

    data class RemovedClassUsage(
        val originalClassName: String,
        val removedMethods: Set<RemovedMethodSignature>,
        val removedFields: Set<String> = emptySet(),
        val isClassRemoved: Boolean = false,
    )

    data class RemovedMethodSignature(
        val name: String,
        val parameterTypes: List<String>,
    )

    fun isClassRemoved(className: String): Boolean {
        return removedClassUsageMap[className]?.isClassRemoved == true
    }

    fun getRemovedMethods(className: String): Set<RemovedMethodSignature> {
        return removedClassUsageMap[className]?.removedMethods ?: emptySet()
    }

    fun isMethodRemoved(className: String, methodName: String, parameterTypes: List<String>): Boolean {
        val signature = RemovedMethodSignature(
            methodName,
            parameterTypes.map(::normalizeTypeName),
        )
        return getRemovedMethods(className).contains(signature)
    }

    fun getRemovedFields(className: String): Set<String> {
        return removedClassUsageMap[className]?.removedFields ?: emptySet()
    }

    fun isFieldRemoved(className: String, fieldName: String): Boolean {
        return getRemovedFields(className).contains(fieldName)
    }

    companion object {

        fun fromFile(file: File): R8UsageReader {
            return fromPath(file.toPath())
        }

        fun fromPath(path: Path): R8UsageReader {
            return fromString(path.toFile().readText())
        }

        fun fromString(content: String): R8UsageReader {
            return R8UsageReader(parseUsageContent(content))
        }

        private fun parseUsageContent(content: String): Map<String, RemovedClassUsage> {
            val builders = linkedMapOf<String, RemovedClassUsageBuilder>()
            var currentClassName: String? = null

            content.lineSequence().forEach { rawLine ->
                if (rawLine.isBlank()) {
                    currentClassName = null
                    return@forEach
                }

                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return@forEach
                }

                val isIndented = rawLine.firstOrNull()?.isWhitespace() == true
                if (!isIndented) {
                    currentClassName = null
                    parseClassLine(trimmed)?.let { classLine ->
                        val builder = builders.getOrPut(classLine.className) {
                            RemovedClassUsageBuilder(classLine.className)
                        }
                        if (classLine.isClassRemoved) {
                            builder.isClassRemoved = true
                        }
                        if (classLine.isHeader) {
                            currentClassName = classLine.className
                        }
                    }
                    return@forEach
                }

                val ownerClass = currentClassName ?: return@forEach
                val memberLine = parseMemberLine(trimmed) ?: return@forEach
                val builder = builders.getOrPut(ownerClass) {
                    RemovedClassUsageBuilder(ownerClass)
                }
                when (memberLine) {
                    is ParsedMemberLine.Method -> builder.removedMethods.add(
                        RemovedMethodSignature(memberLine.name, memberLine.parameterTypes)
                    )
                    is ParsedMemberLine.Field -> builder.removedFields.add(memberLine.name)
                }
            }

            return builders.values.associate { it.className to it.build() }
        }

        private fun parseClassLine(line: String): ParsedClassLine? {
            val className = line.removeSuffix(":")
            if (!isValidClassName(className)) {
                return null
            }
            return ParsedClassLine(
                className = className,
                isHeader = line.endsWith(":"),
                isClassRemoved = !line.endsWith(":"),
            )
        }

        private fun parseMemberLine(line: String): ParsedMemberLine? {
            if (line.contains('(') && line.endsWith(")")) {
                val beforeParen = line.substringBeforeLast('(').trim()
                val methodName = beforeParen.substringAfterLast(' ', "")
                if (methodName.isBlank()) {
                    return null
                }
                val parameterContent = line.substringAfterLast('(').dropLast(1)
                return ParsedMemberLine.Method(
                    name = methodName,
                    parameterTypes = parseParameterList(parameterContent),
                )
            }

            val fieldName = line.substringAfterLast(' ', "")
            if (fieldName.isBlank() || fieldName.contains(' ')) {
                return null
            }
            return ParsedMemberLine.Field(fieldName)
        }

        private fun parseParameterList(parameterContent: String): List<String> {
            if (parameterContent.isBlank()) {
                return emptyList()
            }
            return parameterContent.split(',')
                .map(::normalizeTypeName)
                .filter { it.isNotEmpty() }
        }

        private fun normalizeTypeName(typeName: String): String {
            return typeName.trim().replace(" ", "")
        }

        private fun isValidClassName(className: String): Boolean {
            if (className.isBlank()) {
                return false
            }
            if (className.contains(' ') || className.contains("->") || className.contains('(') || className.contains(')')) {
                return false
            }
            return CLASS_NAME_REGEX.matches(className)
        }

        private val CLASS_NAME_REGEX = Regex("[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*")
    }

    private data class RemovedClassUsageBuilder(
        val className: String,
        val removedMethods: MutableSet<RemovedMethodSignature> = linkedSetOf(),
        val removedFields: MutableSet<String> = linkedSetOf(),
        var isClassRemoved: Boolean = false,
    ) {
        fun build(): RemovedClassUsage {
            return RemovedClassUsage(
                originalClassName = className,
                removedMethods = removedMethods.toSet(),
                removedFields = removedFields.toSet(),
                isClassRemoved = isClassRemoved,
            )
        }
    }

    private data class ParsedClassLine(
        val className: String,
        val isHeader: Boolean,
        val isClassRemoved: Boolean,
    )

    private sealed class ParsedMemberLine {
        data class Method(
            val name: String,
            val parameterTypes: List<String>,
        ) : ParsedMemberLine()

        data class Field(
            val name: String,
        ) : ParsedMemberLine()
    }
}
