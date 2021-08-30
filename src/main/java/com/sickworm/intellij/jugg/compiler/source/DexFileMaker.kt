package com.sickworm.intellij.jugg.compiler.source

import java.io.File

class DexFileMaker {

    fun dex(outputDir: File, classFileOrDir: File) {
        // TODO supports error check
        outputDir.mkdirs()
        val args = "--file-per-class --output $outputDir $classFileOrDir".split(" ").toTypedArray()
        com.android.tools.r8.D8.main(args)
    }
}