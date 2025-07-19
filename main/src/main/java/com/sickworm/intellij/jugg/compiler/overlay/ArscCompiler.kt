package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.util.zip.ZipFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Compile res .flat files to deployable files
 *
 * e.g.
 * input:
 * activity_main.xml.flat
 *
 * output:
 * activity_main.xml (compiled)
 * resources.arsc
 * AndroidManifest.xml
 * R.java
 */
class ArscCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Flat, CompileFile.Type.AndroidManifest)

    override val isNeedOutputDirEmpty = true

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)
    private val rJavaFixer = RJavaFixer(logger)

    private var hasLoaded = false

    private val canCompile: Boolean get() {
        val apks = context.apkInfos
        return apks.size == 1 && (context.apkFile?.exists() == true) && context.androidJar.exists()
    }

    private fun loadTable(): Boolean {
        if (!canCompile) {
            logger.warn("loadTable failed, context can not compile now")
            return false
        }

        logger.debug("aapt2 loadTable start")
        val startTime = System.currentTimeMillis()

        val deployedArsc = context.deployedFiles.find { it.relativeFile.path == ARSC_FILE_NAME }
        val isNeedLoadLatestResApk = deployedArsc != null
        logger.debug("isNeedLoadLatestResApk: $isNeedLoadLatestResApk, deployedArsc: $deployedArsc")

        var resApkFile: File = context.apkFile!!
        if (isNeedLoadLatestResApk) {
            var manifestFile = context.deployedFiles.find { it.relativeFile.path == "AndroidManifest.xml" }?.file
            if (manifestFile == null) {
                manifestFile = File(context.tempCompileDir, "AndroidManifest.xml")
                context.apkFile!!.extractFile("AndroidManifest.xml", manifestFile)
            }

            val latestResApkFile = File(context.tempCompileDir, "res.apk")
            // zip deployedManifestFile and deployedArsc to res.apk
            zipFiles(listOf(manifestFile, deployedArsc!!.file), latestResApkFile)
            resApkFile = latestResApkFile
        }

        var styleableFile: File? = null
        try {
            TimeLogger.start("generateStyleableFile")
            styleableFile = StyleableFileGenerator(logger).generateStyleableFile(context, context.tempCompileDir)
            TimeLogger.end("generateStyleableFile", logger)
        } catch (e: Exception) {
            logger.debug("generateStyleableFile failed, may not be fatal problem", e)
        }
        if (styleableFile == null) {
            logger.debug("generateStyleableFile failed, start aapt2 with no styleableFile")
        }

        val command = """
            |inclink
            |--load
            |--warn-manifest-validation
            |--styleables
            |${styleableFile?.absolutePath ?: "no_styleables_file"}
            |-o no_need_output_path_on_load
            |-I ${context.androidJar}
            |--manifest no_need_manifest_on_load
            |${resApkFile}
        """.trimMargin().replace("\n", " ")

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.info("loadTable error msg (may not be fatal problem): ${result.errorOutput}")
        }
        if (isNeedLoadLatestResApk) {
            resApkFile.delete()
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("aapt2 loadTable end, cost ${costTime}ms")
        hasLoaded = true
        return true
    }

    override fun doCompile(task: CompileTask): CompileResult {
        if (!canCompile) {
            throw JuggInternalException.contextInvalidToCompileArsc()
        }
        if (!hasLoaded || !aapt2Invoker.isAlive()) {
            logger.debug("aapt2 not loaded or dead, loadTable again. hasLoaded: $hasLoaded, isAlive: ${aapt2Invoker.isAlive()}")
            loadTable()
        }

        val flatFiles = task.files.filter { it.type == CompileFile.Type.Flat }.map { it.file }
        val androidManifestFile = task.files.find { it.type == CompileFile.Type.AndroidManifest }?.file
        val result = incLinkCompile(flatFiles, androidManifestFile, task.outputDir)

        if (result.isEmpty()) {
            // reload
            logger.debug("incLink failed, may effects later compilation. release and reinit next time call.")
            aapt2Invoker.release()
            hasLoaded = false

            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "makeResApk failed"))
                Result.failure(error)
            }, emptyList())
        }

        val javaFile = result.find { it.type == CompileOutput.Type.Java }?.file
        if (javaFile?.exists() == true) {
            rJavaFixer.fixIfNeeded(javaFile)
        }

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            result
        )
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    private fun incLinkCompile(flatFiles: List<File>, androidManifest: File?, outputDir: File): List<CompileOutput> {
        val rFileDir = File(outputDir, "rjava")
        val overlayDir = File(outputDir, "overlays")
        rFileDir.mkdirs()
        overlayDir.mkdirs()

        val manifestName = androidManifest?.absolutePath ?: "no_need_compile_manifest"

        val flatFilesArg = flatFiles.joinToString(separator = "\n") { it.absolutePath }
        val commandArg = """
            |inclink
            |-o $overlayDir
            |--output-to-dir
            |--java $rFileDir
            |--manifest $manifestName
        """.trimMargin().replace("\n", " ")
        val command = "$commandArg $flatFilesArg"

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.error("aapt2 invoke failed, error msg: ${result.errorOutput}")
            return emptyList()
        }

        val rFiles = rFileDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Java, it, rFileDir)
        }
        val overlays = overlayDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Res, it, overlayDir)
        }

        // check whether resources has more config created. e.g. layout-v22
        return rFiles + overlays
    }

    override fun warmUp() {
        if (!hasLoaded) {
            loadTable()
        }
    }

    override fun dispose() {
        aapt2Invoker.release()
    }

    private fun zipFiles(files: List<File>, zipFile: File) {
        zipFile.parentFile?.mkdirs()
        if (zipFile.exists()) {
            zipFile.delete()
        }
        // zip using java.util.zip
        val zipOutputStream = ZipOutputStream(zipFile.outputStream())
        files.forEach {
            val zipEntry = ZipEntry(it.name)
            zipOutputStream.putNextEntry(zipEntry)
            zipOutputStream.write(it.readBytes())
            zipOutputStream.closeEntry()
        }
        zipOutputStream.close()
    }

    private fun File.extractFile(zipEntryName: String, destFile: File) {
        ZipFile(this).use { zipFile ->
            val entry = zipFile.getEntry(zipEntryName)
            if (entry != null) {
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { zipFile.getInputStream(entry).copyTo(it) }
            }
        }
    }

}

const val ARSC_FILE_NAME = "resources.arsc"