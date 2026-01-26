package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.logger.TimeLogger
import java.io.File

class ResGuardMappingFileGenerator(
    private val logger: Logger,
) {

    fun generate(context: ICompileContext, outputDir: File): File? {
        try {
            TimeLogger.start("generateResGuardMapping")
            val aapt2IncLinkMappingFile = File(outputDir, "res-guard-mapping.txt")
            val aabResGuardHandler = AabResGuardHandler(
                context.applicationModule?.buildPathInfo?.aabResGuardMappingFile,
                logger,
            )
            aabResGuardHandler.writeAapt2IncLinkMappingFile(aapt2IncLinkMappingFile)
            if (aapt2IncLinkMappingFile.exists()) {
                return aapt2IncLinkMappingFile
            }
            TimeLogger.end("generateResGuardMapping", logger)
            return null
        } catch (e: Exception) {
            logger.debug("generateResGuardMapping failed, may not be fatal problem", e)
            return null
        }
    }
}