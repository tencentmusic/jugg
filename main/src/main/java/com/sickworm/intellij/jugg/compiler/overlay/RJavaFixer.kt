package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.TimeLogger
import java.io.File
import kotlin.math.min

/**
 * Fix R.java error: "constant too much" by split the R class into several classes and extend them.
 */
class RJavaFixer(private val logger: Logger) {

    /** avoid duplicate analyze in small projects */
    private var isNeedFixCache: Boolean? = null

    /**
     * Override R.java file directly if constant is too much
     */
    fun fixIfNeeded(rFile: File) {
        if (isNeedFixCache == false) {
            logger.debug("R.java no need fix by cache")
            return
        }
        if (!rFile.exists()) {
            logger.debug("R.java no need fix for file not exist")
            return
        }

        TimeLogger.start("RJavaFixer analyze")
        val rJavaData = analyze(rFile)
        val isNeedFix = rJavaData.isNeedFix()
        isNeedFixCache = isNeedFix
        TimeLogger.end("RJavaFixer analyze", logger)

        logger.debug("R.java structure: $rJavaData")
        logger.debug("R.java need fix? $isNeedFix")
        if (!isNeedFix) {
            return
        }

        TimeLogger.start("RJavaFixer rewrite")
        val rRewriteData = split(rJavaData)
        writeToFile(rJavaData, rRewriteData, rFile)
        TimeLogger.end("RJavaFixer rewrite", logger)
    }

    private fun analyze(rFile: File): RJavaData {
        val lines = rFile.readLines()
        val classes = mutableListOf<RClassData>()

        var className = "null"
        var classDeclareLine = 0
        var fieldLines = ArrayList<Int>(0)
        lines.forEachIndexed { index, line ->
            if (line.startsWith("  public static final class ")) {
                // store previous class
                if (className != "null") {
                    val rClassData = RClassData(className, classDeclareLine, fieldLines)
                    classes.add(rClassData)
                }

                // record new class
                className = line.substringAfter("  public static final class")
                    .substringBefore("{")
                    .trim()
                classDeclareLine = index
                fieldLines = ArrayList(10240)
            } else if (line.startsWith("    public static final int ")) {
                // only handle int fields, int[] is unnecessary (it can't be too much and more difficult to split)
                fieldLines.add(index)
            }
        }

        if (fieldLines.isNotEmpty()) {
            val rClassData = RClassData(className, classDeclareLine, fieldLines)
            classes.add(rClassData)
        }

        return RJavaData(lines, classes)
    }

    private fun split(rJavaData: RJavaData): RRewriteData {

        val rewriteLines: MutableMap<Int, String> = mutableMapOf()
        val removeLines: MutableSet<Int> = mutableSetOf()
        val newLines: MutableList<String> = mutableListOf()

        val needSplitClasses = rJavaData.classes.filter { it.isNeedSplit }
        needSplitClasses.forEach { rClassData ->
            logger.debug("RJavaFixer split class: ${rClassData.name}")
            var remainFieldLines = rClassData.fieldLines
            var index = 1
            while (remainFieldLines.size > RClassData.MAX_CONSTANT_FIELDS_COUNT) {
                val splitSize = min(
                    RClassData.MAX_CONSTANT_FIELDS_COUNT,
                    remainFieldLines.size - RClassData.MAX_CONSTANT_FIELDS_COUNT)
                remainFieldLines = remainFieldLines.subList(0, remainFieldLines.size - splitSize)

                val splitFieldLines = remainFieldLines.subList(remainFieldLines.size - splitSize, remainFieldLines.size)
                val splitClassName = "${rClassData.name}$index"
                if (index == 1) {
                    rewriteLines[rClassData.classDeclareLine] = rJavaData.lines[rClassData.classDeclareLine]
                        .replace("{", "extends $splitClassName {")
                    newLines.add("  public static class $splitClassName {\n")
                } else {
                    val lastSplitClassName = "${rClassData.name}${index - 1}"
                    newLines.add("  public static class $splitClassName extends $lastSplitClassName {\n")
                }
                newLines.add("    private $splitClassName() {}")

                splitFieldLines.forEach { lineIndex ->
                    removeLines.add(lineIndex)
                    newLines.add(rJavaData.lines[lineIndex])
                }
                newLines.add("  }\n")

                index++
            }
        }

        return RRewriteData(rewriteLines, removeLines, newLines)
    }

    private fun writeToFile(rJavaData: RJavaData, rRewriteData: RRewriteData, file: File) {
        file.writer().use { writer ->
            // write file without split classes fields, and without last line '}'
            val lastLineWithoutBrackets = if (rJavaData.lines[rJavaData.lines.size - 1].trim().isEmpty()) {
                rJavaData.lines.size - 2
            } else {
                rJavaData.lines.size - 1
            }
            for (index in 0 until lastLineWithoutBrackets) {
                if (rRewriteData.removeLines.contains(index)) {
                    continue
                }
                if (rRewriteData.rewriteLines.containsKey(index)) {
                    writer.write(rRewriteData.rewriteLines[index]!!)
                    writer.write("\n")
                    continue
                }
                val line = rJavaData.lines[index]
                writer.write(line)
                writer.write("\n")
            }

            // write file without split classes fields
            rRewriteData.newLines.forEach { line ->
                writer.write(line)
                writer.write("\n")
            }

            // write last line '}'
            writer.write("}\n")
            writer.flush()
        }
    }
}

private class RJavaData(
    /** each line content of R.java */
    val lines: List<String>,
    val classes: List<RClassData>,
) {

    fun isNeedFix(): Boolean {
        return classes.any { it.isNeedSplit }
    }

    override fun toString(): String {
        return "RJavaData(lines=${lines.size}, classes=${classes})"
    }

}

private class RClassData(
    val name: String,
    val classDeclareLine: Int,
    val fieldLines: List<Int>,
) {

    val isNeedSplit: Boolean get() = fieldLines.size > MAX_CONSTANT_FIELDS_COUNT

    override fun toString(): String {
        return "$name: fields=${fieldLines.size}"
    }

    companion object {
        /**
         * constant filed is stored in uint16, and max count is 32756 (tested result in single inner class, about half of 65536)
         * Jugg use 30000 for tolerance. (32700 is also ok)
         */
        const val MAX_CONSTANT_FIELDS_COUNT = 30000
    }
}

private class RRewriteData(
    val rewriteLines: Map<Int, String>,
    val removeLines: Set<Int>,
    val newLines: List<String>,
)
