package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.compiler.ICompileContext
import org.jetbrains.annotations.TestOnly
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.FieldNode
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * StyleableFileGenerator generates styleable output.
 */
class StyleableFileGenerator(
    private val logger: Logger,
) {

    fun generateStyleableFile(context: ICompileContext, outputDir: File, apkFileUnit: ApkFileUnit): File? {
        val selectedApplicationModule = context.applicationModule
        if (selectedApplicationModule == null) {
            logger.warn("generateStyleableFile failed, no application module found")
            return null
        }
        val modules: List<ModuleInfo> = if (context.isSingleApk || apkFileUnit.isBaseApk) {
            listOf(selectedApplicationModule)
        } else {
            context.moduleBelongsApkMap.entries
                .filter { it.value.apkFile.path == apkFileUnit.apkFile.path }
                .map { it.key }
        }
        if (modules.isEmpty()) {
            logger.warn("generateStyleableFile failed, no application module found")
            return null
        }
        logger.debug("generateStyleableFile modules: ${modules.map { it.name } }, apk: $apkFileUnit")

        val rFiles = modules.mapNotNull {
            val rFile = it.buildPathInfo.rFilePath
            if (rFile.exists()) {
                return@mapNotNull rFile
            }
            null
        }
        return if (rFiles.isNotEmpty()) {
            generateStyleableFile(rFiles, outputDir)
        } else {
            // low AGP don't have R.jar, it stored in java classpath
            // won't handle dynamicFeatureModules because I'm lazy to test :)
            logger.debug("generateStyleableFile by java classpath: ${selectedApplicationModule.buildPathInfo.javaClassPath}")

            val manifestFile = selectedApplicationModule.manifestFile
            if (manifestFile == null || !manifestFile.exists()) {
                logger.warn("generateStyleableFile failed, manifest file not found in ${selectedApplicationModule.name}")
                return null
            }
            val packageName = RPackageReader(manifestFile, logger).readPackageName() // we find R package name(namespace) not app package name
            if (packageName == null) {
                logger.warn("generateStyleableFile failed, read package name from manifest file ${manifestFile.absolutePath} failed")
                return null
            }
            generateStyleableFile2(selectedApplicationModule.buildPathInfo.javaClassPath, packageName, outputDir)
        }
    }

    // compat for R split, see: RJavaFixer
    private val availableStyleableNames = listOf("R\$styleable.class", "R\$styleable0.class", "styleable0.class")

    @TestOnly
    fun generateStyleableFile(rFileList: List<File>, outputDir: File): File? {
        val openedJarFile = mutableListOf<ZipFile>()
        rFileList.forEach { rFile ->
            if (!rFile.exists()) {
                logger.warn("generateStyleableFile failed, rFile not exists: ${rFile.absolutePath}")
                return@forEach
            }

            logger.debug("generateStyleableFile, rFile: ${rFile.absolutePath}")

            openedJarFile.add(ZipFile(rFile))
        }

        try {
            val providers = mutableListOf<InputStreamProvider>()
            openedJarFile.forEach { jarFile ->
                val rStyleableEntryList = mutableListOf<ZipEntry>()
                // loop entry
                jarFile.entries().asSequence().forEach { entry ->
                    val isStyleableClass = availableStyleableNames.any { entry.name.endsWith(it) }
                    if (isStyleableClass) {
                        rStyleableEntryList.add(entry)
                    }
                }
                logger.debug("generateStyleableFile found ${rStyleableEntryList.size} styleable class " +
                        "in ${jarFile.name}, detail: ${rStyleableEntryList.joinToString(", ") { it.name }}")

                if (rStyleableEntryList.isEmpty()) {
                    logger.debug("generateStyleableFile rStyleableEntryList not found in ${jarFile.name}")
                    return@forEach
                }
                providers.addAll(rStyleableEntryList.map { InputStreamProvider.of(jarFile, it) })
            }
            return doGenerateStyleableFile(providers, outputDir)
        } finally {
            openedJarFile.forEach { it.close() }
        }
    }

    private fun generateStyleableFile2(rFileDir: File, packageName: String, outputDir: File): File? {
        if (!rFileDir.exists()) {
            logger.warn("generateStyleableFile failed, rFileDir not exists: ${rFileDir.absolutePath}")
            return null
        }

        val rStyleableFileList = mutableListOf<File>()
        availableStyleableNames.forEach { styleableName ->
            val rStyleableFile = File(rFileDir, packageName.replace('.', '/') + "/$styleableName")
            if (rStyleableFile.exists()) {
                logger.debug("$rStyleableFile found in ${rFileDir.absolutePath}")
                rStyleableFileList.add(rStyleableFile)
            }
        }
        logger.debug("generateStyleableFile, rFile: ${rFileDir.absolutePath}, rStyleableFileList: $rStyleableFileList")
        if (rStyleableFileList.isEmpty()) {
            logger.debug("generateStyleableFile failed, rStyleableFileList not found in ${rFileDir.absolutePath}")
            return null
        }

        val providers = rStyleableFileList.map { InputStreamProvider.of(it) }
        return doGenerateStyleableFile(providers, outputDir)
    }

    private fun doGenerateStyleableFile(providers: List<InputStreamProvider>, outputDir: File): File {
        val styleablesMerger = StyleablesMerger(logger)
        providers.forEach { provider ->
            provider.use { ins ->
                val classReader = ClassReader(ins)
                val asmClassNode = ClassNode()
                classReader.accept(asmClassNode, 0)
                asmClassNode.fields.forEach {
                    if (it is FieldNode) {
                        styleablesMerger.acceptVariable(it.name, it.desc)
                    }
                }
            }
        }
        logger.debug("generateStyleableFile success, providers: ${providers.size}, load styleables: ${styleablesMerger.getResult().size}")

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

/**
 * InputStreamProvider abstracts input-stream access from zip entries or plain files.
 */
private interface InputStreamProvider {

    fun use(runnable: (InputStream) -> Unit)

    companion object {

        fun of(zipFile: ZipFile, zipEntry: ZipEntry) = object : InputStreamProvider {
            override fun use(runnable: (InputStream) -> Unit) {
                zipFile.getInputStream(zipEntry).use {
                    runnable(it)
                }
            }
        }

        fun of(file: File) = object : InputStreamProvider {
            override fun use(runnable: (InputStream) -> Unit) {
                file.inputStream().use {
                    runnable(it)
                }
            }
        }
    }

}

/**
 * Styleables carries name and attrs.
 */
private class Styleables(
    val name: String,
    val attrs: MutableList<String>,
)

/**
 * StyleablesMerger merges styleable arrays/attrs collected from class files.
 */
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
