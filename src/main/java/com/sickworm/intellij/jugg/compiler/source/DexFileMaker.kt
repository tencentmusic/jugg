package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import java.io.File


class DexFileMaker {

    fun dex(outputDir: File, classFilesOrDir: List<File>, classpath: Collection<String>): Boolean {
        outputDir.mkdirs()
        // see https://developer.android.com/studio/command-line/d8
        // TODO --lib android.jar?

        val args = mutableListOf<String>()

        args.add("--file-per-class")

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

        try {
            val command = D8Command.parse(args.toTypedArray(), Origin.root()).build()
            com.android.tools.r8.D8.run(command)
        } catch (e: Exception) {
            // TODO supports error return
            e.printStackTrace()
            return false
        }
        return true
    }
}