package com.sickworm.intellij.aidp

import java.io.File

class DexFileMaker {

    fun dex(classDir: File, outputFile: File, classFile: File = classDir) {
        outputFile.parentFile?.mkdirs()
        val jarFilePath = "$outputFile.jar"
        JarFileMaker().jar(classDir, File(jarFilePath), classFile)
        val dexerCli = "D:/Android/sdk/build-tools/30.0.3/dx.bat"
        Runtime.getRuntime().exec("""$dexerCli --dex --min-sdk-version=26 --output=$outputFile $jarFilePath""").waitFor()
        File(jarFilePath).delete()
    }
}