package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import com.intellij.openapi.diagnostic.Logger
import java.io.File

class DexFileMerger(
    private val logger: Logger,
) {

    fun merge(dexFiles: List<File>, outputDir: File) {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val args = mutableListOf<String>()
        args.add("--output")
        args.add(outputDir.absolutePath)
        args.addAll(dexFiles.map { it.absolutePath })

        logger.debug("D8Command: d8 ${args.joinToString(" ")}")

        val builder = D8Command.parse(args.toTypedArray(), Origin.root())
        com.android.tools.r8.D8.run(builder.build()) // throws exceptions
    }
}