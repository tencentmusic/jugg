package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.project.JuggInternalException
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

    override val supportedTypes = listOf(CompileFile.Type.Flat)

    override val isNeedOutputDirEmpty = true

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)

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

        val deployedManifestFile = context.deployedFiles.find { it.relativeFile.path == "AndroidManifest.xml" }
        val deployedArsc = context.deployedFiles.find { it.relativeFile.path == ARSC_FILE_NAME }
        val isNeedLoadLatestResApk = deployedManifestFile != null && deployedArsc != null
        logger.debug("isNeedLoadLatestResApk: $isNeedLoadLatestResApk, deployedManifestFile: $deployedManifestFile, deployedArsc: $deployedArsc")
        if (deployedArsc != null && deployedManifestFile == null) {
            logger.warn("loadTable deployedManifestFile not found, but deployedArsc found, may be fatal problem")
            return false
        }

        var resApkFile: File = context.apkFile!!
        if (isNeedLoadLatestResApk) {
            val latestResApkFile = File(context.tempCompileDir, "res.apk")
            // zip deployedManifestFile and deployedArsc to res.apk
            zipFiles(listOf(deployedManifestFile!!.file, deployedArsc!!.file), latestResApkFile)
            resApkFile = latestResApkFile
        }

        val styleableFile = StyleableFileGenerator(logger).generateStyleableFile(context, context.tempCompileDir)
        if (styleableFile == null) {
            logger.warn("loadTable failed, generateStyleableFile failed")
        }

        val command = """
            |inclink
            |--load
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
            loadTable()
        }

        val flatFiles = task.files.map { it.file }
        val result = incLinkCompile(flatFiles, task.outputDir)

        if (result.isEmpty()) {
            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "makeResApk failed"))
                Result.failure(error)
            }, emptyList())
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

    private fun incLinkCompile(flatFiles: List<File>, outputDir: File): List<CompileOutput> {
        val rFileDir = File(outputDir, "rjava")
        val overlayDir = File(outputDir, "overlays")
        rFileDir.mkdirs()
        overlayDir.mkdirs()

        val flatFilesArg = flatFiles.joinToString(separator = "\n") { it.absolutePath }
        val commandArg = """
            |inclink
            |-o $overlayDir
            |--output-to-dir
            |--java $rFileDir
            |--manifest no_support_manifest_yet
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

}

const val ARSC_FILE_NAME = "resources.arsc"