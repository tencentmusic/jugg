package com.sickworm.intellij.jugg.compiler.custom

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLClassLoader
import java.util.*

class CustomCompilerManager(
    private val projectDir: File,
    private val customCompilerDir: File,
    private val juggServer: JuggServer,
    logger: Logger,
) {

    private val logger = logger.getInstance("CustomCompilerManager")

    private var customCompilerJars = listOf<File>()

    fun updateCustomCompilers(customCompilers: Map<String, String>) {
        logger.debug("updateCustomCompilers $customCompilers")
        if (customCompilers.isEmpty()) {
            return
        }
        customCompilerJars = customCompilers.mapNotNull {
            updateCustomCompiler(it.key, it.value)
        }
        // clear deprecated jars
        customCompilerDir.listFiles()?.forEach { file ->
            if (!customCompilerJars.contains(file)) {
                logger.debug("custom compiler $file deprecated, delete it")
                file.delete()
            }
        }
        logger.debug("updateCustomCompilers finished, compilers: $customCompilerJars")

        // download compilers if not downloaded
        juggServer.launch {
            downloadCompilers(customCompilers)
        }
    }

    private fun updateCustomCompiler(name: String, path: String): File? {
        val absFile = File(path)
        if (absFile.isAbsolute && absFile.exists()) {
            logger.debug("custom compiler $absFile exists, add it directly")
            return absFile
        }
        val relativeFile = File(projectDir, path)
        if (relativeFile.exists()) {
            logger.debug("custom compiler $relativeFile exists, add it directly")
            return relativeFile
        }

        if (path.startsWith("http")) {
            val targetFile = customCompilerDir.resolve(name)
            if (targetFile.exists()) {
                logger.debug("http target file $targetFile exists, add it directly")
                return targetFile
            } else {
                logger.debug("http target file $targetFile not exists, download it later")
                return null
            }
        }

        logger.debug("unknown path $path, ignore")
        return null
    }

    private fun downloadCompilers(customCompilers: Map<String, String>) {
        customCompilers.forEach { (name, path) ->
            if (path.startsWith("http")) {
                downloadCompiler(name, path)
            }
        }
    }

    private fun downloadCompiler(name: String, path: String) {
        val targetFile = customCompilerDir.resolve(name)
        try {
            juggServer.downloadFile(path, targetFile)
            logger.debug("target file $targetFile download finished")
        } catch (e: Exception) {
            logger.warn("error downloading target file $targetFile, skip. error: $e")
        }
    }

}