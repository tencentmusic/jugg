package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import java.io.File


class DexFileMaker {

    fun dex(outputDir: File, classFileOrDir: File): Boolean {
        outputDir.mkdirs()
        val args = "--file-per-class --output $outputDir $classFileOrDir".split(" ").toTypedArray()

        try {
            val command = D8Command.parse(args, Origin.root()).build()
            com.android.tools.r8.D8.run(command)
        } catch (e: Exception) {
            // TODO supports error return
            e.printStackTrace()
            return false
        }
        return true
    }
}