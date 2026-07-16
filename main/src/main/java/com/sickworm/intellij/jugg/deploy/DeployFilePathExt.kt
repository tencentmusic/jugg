package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.JuggInternalException
import java.io.File
import java.util.zip.CRC32

internal val File.stdPath: String
    get() = path.replace(File.separatorChar, '/')

internal val File.stdAbsPath: String
    get() = absolutePath.replace(File.separatorChar, '/')


private val crc32 = CRC32()

fun CompileOutput.toDeployItem(deployName: String = deployItemName): DeployItem {
    val bytes = file.readBytes()
    val crc = crc32.run {
        reset()
        update(bytes)
        value
    }
    when (type) {
        CompileOutput.Type.Dex -> {
            return DeployItem(deployName, type, crc, bytes, DeployItem.FLAG_CLASS, targetApkPaths)
        }
        CompileOutput.Type.Res, CompileOutput.Type.Asset, CompileOutput.Type.NativeLib -> {
            if (apkPath == null) {
                throw JuggInternalException.outputDidNotSpecificApkPath(this.toString())
            }
            return DeployItem(deployName, type, crc, bytes, apkPath, targetApkPaths)
        }
        else -> {
            return DeployItem(deployName, type, crc, bytes, DeployItem.FLAG_BASE_APK) // will not apply to device
        }
    }
}

val CompileOutput.deployItemName: String get() {
    return if (type == CompileOutput.Type.Dex) {
        relativeFile.stdPath
            .replace('/', '.')
            .replace(file.name, file.nameWithoutExtension)
    } else {
        relativeFile.stdPath
    }
}
