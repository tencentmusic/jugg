package com.sickworm.intellij.jugg.compiler.source

import com.sickworm.intellij.jugg.isWindows
import java.io.File

class DexFileMaker(private val androidBuildTools: File) {

    fun dex(classDir: File, outputFile: File, classFile: File = classDir) {
        outputFile.parentFile?.mkdirs()
        val jarFilePath = "$outputFile.jar"
        JarFileMaker().jar(classDir, File(jarFilePath), classFile)
        val dxName = if (isWindows) "dx.bat" else "dx"
        val dxCli = "${androidBuildTools.absolutePath}/$dxName"
        Runtime.getRuntime().exec("""$dxCli --dex --min-sdk-version=26 --output=$outputFile $jarFilePath""").waitFor()
        File(jarFilePath).delete()
    }
}