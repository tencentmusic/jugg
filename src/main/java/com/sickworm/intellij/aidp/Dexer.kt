package com.sickworm.intellij.aidp

import java.io.File

class Dexer {

    fun dex(buildPath: File, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        val jarFilePath = "$outputFile.jar"
        val dexerCli = "D:/Android/sdk/build-tools/30.0.3/dx.bat"
        Runtime.getRuntime().exec("""jar cvf $jarFilePath -C $buildPath .""").waitFor()
        Runtime.getRuntime().exec("""$dexerCli --dex --output=$outputFile $jarFilePath""").waitFor()
        File(jarFilePath).delete()
        // dx --min-sdk-version
    }
}