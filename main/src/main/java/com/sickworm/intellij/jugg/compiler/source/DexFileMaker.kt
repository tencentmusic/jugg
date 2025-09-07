package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import com.intellij.openapi.diagnostic.Logger
import java.io.File


class DexFileMaker(private val logger: Logger) {

    fun dex(outputDir: File,
            classFilesOrDir: List<File>,
            classpath: Collection<String>,
            androidJar: File,
            minApi: Int,
            isFilePerClass: Boolean = true,
            desugaredLibraryConfiguration: String? = null,
    ) {
        outputDir.mkdirs()

        // see https://developer.android.com/studio/command-line/d8
        val args = mutableListOf<String>()

        if (isFilePerClass) {
            args.add("--file-per-class")
        }

        args.add("--lib")
        args.add(androidJar.absolutePath)

        args.add("--min-api")
        args.add("$minApi")

        // see:
        // https://developer.android.com/tools/d8#j8
        if (classpath.isNotEmpty()) {
            classpath.forEach {
                args.add("--classpath")
                args.add(it)
            }
        }

        args.add("--output")
        args.add(outputDir.absolutePath)

        val filesPath = classFilesOrDir.map { it.absolutePath }
        args.addAll(filesPath)

        val builder = D8Command.parse(args.toTypedArray(), Origin.root())
        if (desugaredLibraryConfiguration != null) {
            builder.addDesugaredLibraryConfiguration(desugaredLibraryConfiguration)
        }
        val command = builder.build()
        logger.debug("D8Command: d8 ${args.joinToString(" ")}")
        com.android.tools.r8.D8.run(command) // throws exceptions
    }
}