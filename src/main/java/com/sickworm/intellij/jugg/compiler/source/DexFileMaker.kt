package com.sickworm.intellij.jugg.compiler.source

import java.io.File

class DexFileMaker {

    fun dex(classDir: File, outputFile: File, classFile: File = classDir) {
        outputFile.parentFile?.mkdirs()
        val jarFilePath = "$outputFile.jar" // copy from build-tools/30.0.3/lib
        JarFileMaker().jar(classDir, File(jarFilePath), classFile)
        val args = "--dex --min-sdk-version=26 --output=$outputFile $jarFilePath".split(" ").toTypedArray()
        com.android.dx.command.Main.main(args)
        File(jarFilePath).delete()
    }
}