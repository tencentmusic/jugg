package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import org.jetbrains.annotations.TestOnly
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.FieldNode
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class StyleableFileGenerator(
    private val logger: Logger,
) {

    fun generateStyleableFile(context: ICompileContext, outputDir: File): File? {
        val selectedApplicationModule = context.applicationModule
        if (selectedApplicationModule == null) {
            logger.warn("generateStyleableFile failed, no application module found")
            return null
        }
        logger.debug("application module: ${selectedApplicationModule.name}")

        val manifestFile = selectedApplicationModule.manifestFile
        if (manifestFile == null || !manifestFile.exists()) {
            logger.warn("generateStyleableFile failed, manifest file not found in ${selectedApplicationModule.name}")
            return null
        }
        val packageName = RPackageReader(manifestFile, logger).readPackageName()
        if (packageName == null) {
            logger.warn("generateStyleableFile failed, read package name from manifest file ${manifestFile.absolutePath} failed")
            return null
        }

        val rFile = selectedApplicationModule.buildPathInfo.rFilePath
        return if (rFile.exists()) {
            logger.debug("generateStyleableFile by rFile: ${rFile.absolutePath}")
            generateStyleableFile(rFile, packageName, outputDir)
        } else {
            logger.debug("generateStyleableFile by java classpath: ${selectedApplicationModule.buildPathInfo.javaClassPath}")
            // low AGP don't have R.jar, it stored in java classpath
            generateStyleableFile2(selectedApplicationModule.buildPathInfo.javaClassPath, packageName, outputDir)
        }
    }

    @TestOnly
    fun generateStyleableFile(rFile: File, packageName: String, outputDir: File): File? {
        if (!rFile.exists()) {
            logger.warn("generateStyleableFile failed, rFile not exists: ${rFile.absolutePath}")
            return null
        }

        val rStyleableEntryName = packageName.replace('.', '/') + "/R\$styleable.class"
        logger.debug("generateStyleableFile, rFile: ${rFile.absolutePath}, rStyleableEntryName: $rStyleableEntryName")
        ZipFile(rFile).use { jarFile ->
            val rStyleableEntry = jarFile.getEntry(rStyleableEntryName)
            if (rStyleableEntry == null) {
                logger.debug("generateStyleableFile failed, $rStyleableEntryName not found in ${rFile.absolutePath}")
                return null
            }

            jarFile.getInputStream(rStyleableEntry).use { ins ->
                return generateStyleableFile(ins, outputDir)
            }
        }
    }

    private fun generateStyleableFile2(rFileDir: File, packageName: String, outputDir: File): File? {
        if (!rFileDir.exists()) {
            logger.warn("generateStyleableFile failed, rFileDir not exists: ${rFileDir.absolutePath}")
            return null
        }

        val rStyleableFile = File(rFileDir, packageName.replace('.', '/') + "/R\$styleable.class")
        logger.debug("generateStyleableFile, rFile: ${rFileDir.absolutePath}, rStyleableFile: $rStyleableFile")
        if (!rStyleableFile.exists()) {
            logger.debug("generateStyleableFile failed, $rStyleableFile not found in ${rFileDir.absolutePath}")
            return null
        }

        rStyleableFile.inputStream().use { ins ->
            return generateStyleableFile(ins, outputDir)
        }
    }

    private fun generateStyleableFile(ins: InputStream, outputDir: File): File {
        val styleablesMerger = StyleablesMerger(logger)
        val classReader = ClassReader(ins)
        val asmClassNode = ClassNode()
        classReader.accept(asmClassNode, 0)
        asmClassNode.fields.forEach {
            if (it is FieldNode) {
                styleablesMerger.acceptVariable(it.name, it.desc)
            }
        }
        logger.debug("generateStyleableFile success, load styleables: ${styleablesMerger.getResult().size}")

        val outputFile = File(outputDir, "styleables.txt")
        outputFile.parentFile?.mkdirs()

        BufferedOutputStream(outputFile.outputStream()).use { outs ->
            styleablesMerger.getResult().forEach {
                outs.write("${it.name}:".toByteArray())
                outs.write(it.attrs.joinToString(",").toByteArray())
                outs.write("\n".toByteArray())
            }
            outs.flush()
        }
        return outputFile
    }
}


private class Styleables(
    val name: String,
    val attrs: MutableList<String>,
)

private class StyleablesMerger(private val logger: Logger) {

    private val styleables = mutableMapOf<String, Styleables>()

    private var currentStyleableName: String? = null

    fun acceptVariable(name: String, type: String) {
        when (type) {
            "[I" -> {
                styleables[name] = Styleables(name, mutableListOf())
                currentStyleableName = name
            }
            "I" -> {
                val currentStyleableName = currentStyleableName
                if (currentStyleableName == null || !name.startsWith(currentStyleableName)) {
                    logger.warn("acceptVariable get int type, but currentStyleableName is null or not match, " +
                            "name: $name, currentStyleableName: $currentStyleableName")
                    return
                }
                val attrName = name.substring(currentStyleableName.length + 1)
                styleables[currentStyleableName]!!.attrs.add(attrName)
            }
            else -> {
                logger.warn("acceptVariable get unknown type: $type, with name: $name")
            }
        }
    }

    fun getResult(): List<Styleables> {
        return styleables.values.toList()
    }
}