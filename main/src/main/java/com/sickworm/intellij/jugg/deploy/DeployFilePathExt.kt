package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.util.zip.CRC32

internal val File.stdPath: String
    get() = path.replace(File.separatorChar, '/')

internal val File.stdAbsPath: String
    get() = absolutePath.replace(File.separatorChar, '/')


fun CompileOutput.toDeployItem(deployName: String = deployItemName): DeployItem {
    val size = file.length()
    if (size > Int.MAX_VALUE) {
        if (type != CompileOutput.Type.NativeLib) {
            throw JuggInternalException.outputTooLargeToDeploy(file, size)
        }
        val outputApkPath = apkPath
            ?: throw JuggInternalException.outputDidNotSpecificApkPath(this.toString())
        val lastModified = file.lastModified()
        val crc = file.streamCrc32()
        return DeployItem.fileBackedNativeLib(
            deployName,
            crc,
            file,
            size,
            lastModified,
            outputApkPath,
            targetApkPaths,
        )
    }
    val bytes = file.readBytes()
    val crc = CRC32().run {
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

private fun File.streamCrc32(): Long {
    val crc = CRC32()
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            crc.update(buffer, 0, count)
        }
    }
    return crc.value
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
