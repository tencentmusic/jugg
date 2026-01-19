package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Path

/**
 * R8FileMaker is responsible for running R8 to produce optimized and obfuscated DEX files.
 *
 * R8 combines DEX compilation, optimization, shrinking, and obfuscation in a single step.
 * It replaces the need to run D8 + ProGuard separately.
 *
 * @param logger Logger for debug and error messages
 */
class R8FileMaker(private val logger: Logger) {

    /**
     * Run R8 to produce optimized and obfuscated DEX output.
     *
     * @param outputDir Output directory for DEX files and mapping file
     * @param classFilesOrDir List of class files or directories to process
     * @param classpath Classpath for resolving dependencies
     * @param androidJar Path to android.jar for API resolution
     * @param minApi Minimum Android API level
     * @param proguardRules List of ProGuard rules files for configuration
     * @param isFilePerClass Whether to output one DEX file per class (useful for debugging)
     * @param desugaredLibraryConfiguration Optional desugared library configuration
     * @param mappingOutput Optional path for output mapping file (default: outputDir/mapping.txt)
     */
    fun optimize(
        outputDir: File,
        classFilesOrDir: List<File>,
        classpath: Collection<String>,
        androidJar: File,
        minApi: Int,
        proguardRules: List<File> = emptyList(),
        isFilePerClass: Boolean = false,
        desugaredLibraryConfiguration: String? = null,
        mappingInput: File? = null,
        mappingOutput: File? = null,
    ) {
        outputDir.mkdirs()

        // Build R8 command
        val builder = R8Command.builder()
            .setMinApiLevel(minApi)
            .addLibraryFiles(androidJar.toPath())
            .setOutput(outputDir.toPath(), com.android.tools.r8.OutputMode.DexIndexed)

        // Add input files
        // R8 doesn't support adding directories directly, we need to recursively find all .class and .jar files
        val programFiles = mutableListOf<Path>()
        classFilesOrDir.forEach { file ->
            if (file.isDirectory) {
                // Recursively find all .class and .jar files
                file.walkTopDown().forEach { child ->
                    if (child.isFile && (child.extension == "class" || child.extension == "jar")) {
                        programFiles.add(child.toPath())
                    }
                }
            } else if (file.extension == "class" || file.extension == "jar") {
                programFiles.add(file.toPath())
            }
        }

        if (programFiles.isNotEmpty()) {
            builder.addProgramFiles(programFiles)
        }

        // Add classpath
        if (classpath.isNotEmpty()) {
            val classpathPaths = classpath.map { Path.of(it) }
            builder.addClasspathFiles(classpathPaths)
        }

        // Add ProGuard configuration files
        proguardRules.forEach { rulesFile ->
            if (rulesFile.exists()) {
                builder.addProguardConfigurationFiles(rulesFile.toPath())
            } else {
                logger.warn("ProGuard rules file not found: ${rulesFile.absolutePath}")
            }
        }

        if (mappingInput != null) {
            builder.setProguardMapInputFile(mappingInput.toPath())
        }

        // Set mapping output file
        val actualMappingOutput = mappingOutput ?: File(outputDir, "mapping.txt")
        builder.setProguardMapOutputPath(actualMappingOutput.toPath())

        // File-per-class mode (for debugging)
        if (isFilePerClass) {
            builder.setOutput(outputDir.toPath(), com.android.tools.r8.OutputMode.DexFilePerClassFile)
        }

        // Add desugared library configuration if specified
        if (desugaredLibraryConfiguration != null) {
            builder.addDesugaredLibraryConfiguration(desugaredLibraryConfiguration)
        }

        val command = builder.build()

        // Log the R8 command configuration
        logger.debug("R8Command configuration:")
        logger.debug("  Output: ${outputDir.absolutePath}")
        logger.debug("  MinApi: $minApi")
        logger.debug("  Library: ${androidJar.absolutePath}")
        logger.debug("  ProGuard rules: ${proguardRules.joinToString(", ") { it.absolutePath }}")
        logger.debug("  Input files: ${classFilesOrDir.joinToString(", ") { it.absolutePath }}")
        logger.debug("  Classpath: ${classpath.joinToString(", ")}")
        logger.debug("  Mapping output: ${actualMappingOutput.absolutePath}")
        logger.debug("  File per class: $isFilePerClass")

        // Run R8
        try {
            R8.run(command) // throws exceptions on error
            logger.debug("R8 execution completed successfully")
        } catch (e: Exception) {
            logger.error("R8 execution failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Run R8 with ProGuard rules content directly (instead of files).
     *
     * @param outputDir Output directory for DEX files and mapping file
     * @param classFilesOrDir List of class files or directories to process
     * @param classpath Classpath for resolving dependencies
     * @param androidJar Path to android.jar for API resolution
     * @param minApi Minimum Android API level
     * @param proguardRulesContent ProGuard rules as string content
     * @param isFilePerClass Whether to output one DEX file per class
     * @param desugaredLibraryConfiguration Optional desugared library configuration
     * @param mappingOutput Optional path for output mapping file
     */
    fun optimizeWithRulesContent(
        outputDir: File,
        classFilesOrDir: List<File>,
        classpath: Collection<String>,
        androidJar: File,
        minApi: Int,
        proguardRulesContent: String,
        isFilePerClass: Boolean = false,
        desugaredLibraryConfiguration: String? = null,
        mappingOutput: File? = null,
    ) {
        // Write rules content to a temporary file
        val tempRulesFile = File.createTempFile("proguard-rules", ".pro")
        try {
            tempRulesFile.writeText(proguardRulesContent)
            optimize(
                outputDir = outputDir,
                classFilesOrDir = classFilesOrDir,
                classpath = classpath,
                androidJar = androidJar,
                minApi = minApi,
                proguardRules = listOf(tempRulesFile),
                isFilePerClass = isFilePerClass,
                desugaredLibraryConfiguration = desugaredLibraryConfiguration,
                mappingOutput = mappingOutput
            )
        } finally {
            tempRulesFile.delete()
        }
    }
}
