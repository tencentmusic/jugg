package com.sickworm.intellij.jugg.compiler.source

import java.io.File

class DexFileMaker {

    fun dex(classDir: File, outputFile: File, classFile: File = classDir) {
        outputFile.parentFile?.mkdirs()
        val jarFilePath = "$outputFile.jar"
        JarFileMaker().jar(classDir, File(jarFilePath), classFile)
        val args = "--dex --min-sdk-version=26 --output=$outputFile $jarFilePath".split(" ").toTypedArray()
        com.android.dx.command.Main.main(args)
        File(jarFilePath).delete()
    }
}