package com.sickworm.intellij.jugg.tools

import java.io.File
import java.util.jar.JarFile

/**
 * Command-line tool that scans processor jars and prints per-processor
 * incremental type for routing strategy:
 * - isolating -> can try single-file compile
 * - aggregating/unknown -> should fallback to full compile
 */
object AnnotationProcessorTypeDetectorTool {

    private const val APT_SERVICE_FILE = "META-INF/services/javax.annotation.processing.Processor"
    private const val KSP_SERVICE_FILE = "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
    private const val GRADLE_INCREMENTAL_FILE = "META-INF/gradle/incremental.annotation.processors"

    @JvmStatic
    fun main(args: Array<String>) {
        val jars = resolveInputJars(args)
        if (jars.isEmpty()) {
            printUsageAndExit("No valid jar found in input.")
        }

        val results = jars.map { analyzeJar(it) }
        printResult(results)
    }

    /**
     * Resolve jars from:
     * 1) direct jar path
     * 2) directory path (recursive *.jar)
     * 3) "--from-file <file>" where file lists jar/dir paths line by line
     * 4) argfile style "@<file>"
     */
    private fun resolveInputJars(args: Array<String>): List<File> {
        if (args.isEmpty()) {
            printUsageAndExit("Missing input.")
        }

        val rawPaths = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                "--from-file", "-f" -> {
                    val next = args.getOrNull(index + 1)
                        ?: printUsageAndExit("Missing file path after $arg")
                    rawPaths += loadPathsFromListFile(File(next))
                    index += 2
                    continue
                }
                else -> rawPaths += arg
            }
            index++
        }

        val expanded = mutableListOf<String>()
        rawPaths.forEach { item ->
            if (item.startsWith("@")) {
                expanded += loadPathsFromListFile(File(item.removePrefix("@")))
                return@forEach
            }
            if (item.contains(File.pathSeparatorChar) && !File(item).exists()) {
                expanded += item.split(File.pathSeparatorChar).filter { it.isNotBlank() }
                return@forEach
            }
            expanded += item
        }

        val jars = mutableListOf<File>()
        expanded
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { path ->
                val file = File(path)
                if (!file.exists()) {
                    System.err.println("[WARN] Path not found: ${file.path}")
                    return@forEach
                }
                if (file.isDirectory) {
                    file.walkTopDown()
                        .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                        .forEach { jars += it }
                } else if (file.isFile && file.extension.equals("jar", ignoreCase = true)) {
                    jars += file
                } else {
                    System.err.println("[WARN] Not a jar or directory: ${file.path}")
                }
            }

        return jars
            .map { it.absoluteFile.normalize() }
            .distinctBy { it.path }
            .sortedBy { it.path }
    }

    private fun loadPathsFromListFile(file: File): List<String> {
        if (!file.exists() || !file.isFile) {
            System.err.println("[WARN] List file not found: ${file.path}")
            return emptyList()
        }
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }

    /**
     * Analyze one jar by combining:
     * - APT service declaration
     * - KSP service declaration
     * - Gradle incremental processor metadata
     */
    private fun analyzeJar(jar: File): JarAnalyzeResult {
        JarFile(jar).use { jarFile ->
            val aptProcessors = readServiceClasses(jarFile, APT_SERVICE_FILE)
            val kspProviders = readServiceClasses(jarFile, KSP_SERVICE_FILE)
            val metadataTypeMap = readGradleIncrementalMetadata(jarFile)

            val processors = mutableListOf<ProcessorInfo>()
            aptProcessors.forEach { className ->
                processors += ProcessorInfo(
                    className = className,
                    kind = ProcessorKind.APT,
                    type = metadataTypeMap[className] ?: ProcessorType.UNKNOWN,
                )
            }
            kspProviders.forEach { className ->
                processors += ProcessorInfo(
                    className = className,
                    kind = ProcessorKind.KSP,
                    type = metadataTypeMap[className] ?: ProcessorType.UNKNOWN,
                )
            }
            metadataTypeMap.forEach { (className, type) ->
                if (processors.none { it.className == className }) {
                    processors += ProcessorInfo(
                        className = className,
                        kind = ProcessorKind.META_ONLY,
                        type = type,
                    )
                }
            }

            val summaryType = if (processors.isEmpty()) {
                JarSummaryType.NO_PROCESSOR
            } else if (processors.all { it.type == ProcessorType.ISOLATING }) {
                JarSummaryType.ISOLATING_ONLY
            } else {
                JarSummaryType.HAS_AGGREGATING_OR_UNKNOWN
            }
            return JarAnalyzeResult(jar, processors.sortedBy { it.className }, summaryType)
        }
    }

    private fun readServiceClasses(jarFile: JarFile, entryName: String): List<String> {
        val entry = jarFile.getJarEntry(entryName) ?: return emptyList()
        return jarFile.getInputStream(entry).bufferedReader().useLines { lines ->
            lines
                .map { it.substringBefore("#").trim() }
                .filter { it.isNotBlank() }
                .toList()
        }
    }

    private fun readGradleIncrementalMetadata(jarFile: JarFile): Map<String, ProcessorType> {
        val entry = jarFile.getJarEntry(GRADLE_INCREMENTAL_FILE) ?: return emptyMap()
        return jarFile.getInputStream(entry).bufferedReader().useLines { lines ->
            val map = linkedMapOf<String, ProcessorType>()
            lines.forEach { raw ->
                val line = raw.substringBefore("#").trim()
                if (line.isBlank()) {
                    return@forEach
                }
                val parts = line.split(",").map { it.trim() }
                if (parts.isEmpty() || parts[0].isBlank()) {
                    return@forEach
                }
                val className = parts[0]
                val rawType = parts.getOrNull(1)?.lowercase() ?: ""
                val type = when (rawType) {
                    "isolating" -> ProcessorType.ISOLATING
                    "aggregating" -> ProcessorType.AGGREGATING
                    else -> ProcessorType.UNKNOWN
                }
                map[className] = type
            }
            map
        }
    }

    private fun printResult(results: List<JarAnalyzeResult>) {
        println("=== Annotation Processor Type Report ===")
        println("jarCount: ${results.size}")
        println()

        results.forEach { result ->
            println("jar: ${result.jar.path}")
            println("summary: ${result.summaryType.printable}")
            if (result.processors.isEmpty()) {
                println("processors: (none)")
            } else {
                println("processors:")
                result.processors.forEach { processor ->
                    println("  - [${processor.kind.name}] ${processor.className} -> ${processor.type.printable}")
                }
            }
            println()
        }

        val canSingleFile = results.all {
            it.summaryType == JarSummaryType.NO_PROCESSOR || it.summaryType == JarSummaryType.ISOLATING_ONLY
        }
        val finalStrategy = if (canSingleFile) {
            "single-file"
        } else {
            "full-module"
        }

        println("=== Global Decision ===")
        println("recommendedStrategy: $finalStrategy")
        println("rule: any aggregating/unknown => full-module")
    }

    private fun printUsageAndExit(errorMessage: String): Nothing {
        System.err.println("[ERROR] $errorMessage")
        System.err.println()
        System.err.println(
            """
            Usage:
              1) Direct jars:
                 AnnotationProcessorTypeDetectorTool <a.jar> <b.jar> ...
              2) From list file:
                 AnnotationProcessorTypeDetectorTool --from-file jars.txt
              3) Mixed:
                 AnnotationProcessorTypeDetectorTool @jars.txt /path/to/dir /path/to/a.jar
            
            List file format:
              - one path per line
              - supports jar path or directory path
              - lines starting with # are ignored
            """.trimIndent()
        )
        kotlin.system.exitProcess(1)
    }

    private data class JarAnalyzeResult(
        val jar: File,
        val processors: List<ProcessorInfo>,
        val summaryType: JarSummaryType,
    )

    private data class ProcessorInfo(
        val className: String,
        val kind: ProcessorKind,
        val type: ProcessorType,
    )

    private enum class ProcessorKind {
        APT,
        KSP,
        META_ONLY,
    }

    private enum class ProcessorType(val printable: String) {
        ISOLATING("isolating"),
        AGGREGATING("aggregating"),
        UNKNOWN("unknown"),
    }

    private enum class JarSummaryType(val printable: String) {
        NO_PROCESSOR("no-processor"),
        ISOLATING_ONLY("isolating"),
        HAS_AGGREGATING_OR_UNKNOWN("aggregating/unknown"),
    }
}

