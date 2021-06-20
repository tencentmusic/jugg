package com.sickworm.intellij.aidp

import java.io.File

class Dexer {

    fun dex(buildPath: String, outputDir: String) {
        File(outputDir).mkdirs()
        val jarFilePath = "$outputDir/out.jar"
        val dexerCli = "D:/Android/sdk/build-tools/30.0.3/dx.bat"
        val dexFilePath = "$outputDir/out.dex"
        Runtime.getRuntime().exec("""jar cvf $jarFilePath -C $buildPath .""").waitFor()
        Runtime.getRuntime().exec("""$dexerCli --dex --output=$dexFilePath $jarFilePath""").waitFor()
        File(jarFilePath).delete()
        // dx --min-sdk-version
    }
}