package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.JuggInternalException
import java.util.zip.ZipFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    private val rJavaFixer = RJavaFixer(logger)

    private val aapt2InvokerMap: ConcurrentHashMap<String, Aapt2DaemonInvoker> = ConcurrentHashMap()

    private val canCompile: Boolean get() {
        return context.androidJar.exists()
    }

    private fun loadTable(apkFileUnit: ApkFileUnit): Boolean {
        if (!canCompile) {
            logger.warn("loadTable failed, context can not compile now")
            return false
        }

        logger.debug("aapt2 loadTable start for $apkFileUnit")
        val startTime = System.currentTimeMillis()

        val resApkFile: File = getResApk(apkFileUnit)
        var styleableFile: File? = null
        try {
            TimeLogger.start("generateStyleableFile")
            styleableFile = StyleableFileGenerator(logger).generateStyleableFile(context, context.tempCompileDir, apkFileUnit)
            TimeLogger.end("generateStyleableFile", logger)
        } catch (e: Exception) {
            logger.debug("generateStyleableFile failed, may not be fatal problem", e)
        }
        if (styleableFile == null) {
            logger.debug("generateStyleableFile failed, start aapt2 with no styleableFile")
        }
        val resGuardMappingFile = ResGuardMappingFileGenerator(logger).generate(context, context.tempCompileDir)

        val aapt2Invoker = Aapt2DaemonInvoker(logger)
        val command = mutableListOf(
            "inclink",
            "--load",
            "--warn-manifest-validation",
            "--res-guard-mapping",
            resGuardMappingFile?.absolutePath ?: "no_res_guard_mapping_file",
            "--styleables",
            styleableFile?.absolutePath ?: "no_styleables_file",
            "-o",
            "no_need_output_path_on_load",
            "-I",
            context.androidJar.absolutePath,
        )
        if (!context.isSingleApk && !apkFileUnit.isBaseApk) {
            val baseApk = context.apkInfos
                .find { it.applicationId == context.packageName }
                ?.files?.find { it.isBaseApk }
            if (baseApk == null) {
                throw JuggException.baseApkNotFound(context.packageName, context.apkInfos)
            }
            val baseResApk = getResApk(baseApk)
            command.addAll(listOf("-I", baseResApk.absolutePath))
        }
        command.addAll(listOf("--manifest", "no_need_manifest_on_load", resApkFile.absolutePath))

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.warn("loadTable failed: ${result.errorOutput}")
            aapt2Invoker.release()
            return false
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("aapt2 loadTable end for $apkFileUnit, cost ${costTime}ms")
        aapt2InvokerMap[apkFileUnit.apkFile.path] = aapt2Invoker
        return true
    }

    private fun getResApk(apkFileUnit: ApkFileUnit): File {
        val deployedArsc = context.deployedFiles.find {
            it.apkPath == apkFileUnit.apkFile.path && it.relativeFile.path == ARSC_FILE_NAME
        }
        val isNeedLoadLatestResApk = deployedArsc != null
        logger.debug("${apkFileUnit.apkFile.name} isNeedLoadLatestResApk: $isNeedLoadLatestResApk, deployedArsc: $deployedArsc")

        var resApkFile: File = apkFileUnit.apkFile
        if (isNeedLoadLatestResApk) {
            // apk is deployed before, needs load latest res
            var manifestFile = context.deployedFiles.find {
                it.apkPath == apkFileUnit.apkFile.path && it.relativeFile.path == "AndroidManifest.xml"
            }?.file
            if (manifestFile == null) {
                manifestFile = File(context.tempCompileDir, "AndroidManifest.xml")
                manifestFile.delete()
                resApkFile.extractFile("AndroidManifest.xml", manifestFile)
            }

            val latestResApkFile = File(context.tempCompileDir, "${apkFileUnit.getUniquePath("res")}.apk")
            if (latestResApkFile.exists()) {
                latestResApkFile.delete()
            }
            // zip deployedManifestFile and deployedArsc to res.apk
            zipFiles(listOf(manifestFile, deployedArsc!!.file), latestResApkFile)
            resApkFile = latestResApkFile
        }

        return resApkFile
    }

    override fun doCompile(task: CompileTask): CompileResult {
        return splitApkAndCompile(task)
    }

    private var isBaseApkArscUpdate = false
    private var baseApkUpdateFlatFiles = listOf<File>()

    override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
        if (!canCompile) {
            throw JuggInternalException.contextInvalidToCompileArsc()
        }
        var aapt2Invoker = aapt2InvokerMap[apkFileUnit.apkFile.path]
        if (aapt2Invoker == null || !aapt2Invoker.isAlive()) {
            logger.debug("aapt2 not loaded or dead for ${apkFileUnit.apkFile.path}, run loadTable. " +
                    "hasLoaded: ${aapt2Invoker != null}, isAlive: ${aapt2Invoker?.isAlive()}")
            if (!loadTable(apkFileUnit)) {
                return CompileResult(task, task.files.map {
                    val error = CompileError(it, listOf(0L to "loadTable failed"))
                    Result.failure(error)
                }, emptyList())
            }
            aapt2Invoker = aapt2InvokerMap[apkFileUnit.apkFile.path]!!
        }

        val flatFiles = task.files.filter { it.type == CompileFile.Type.Flat }.map { it.file }.toMutableList()
        if (apkFileUnit.isFeatureApk && isBaseApkArscUpdate) {
            // base apk res.arsc updated, need join compiled
            logger.debug("base apk res.arsc updated, join compiled for ${apkFileUnit.apkFile.path}, files: $baseApkUpdateFlatFiles")
            flatFiles.addAll(baseApkUpdateFlatFiles)
        }

        val androidManifestFile = task.files.find { it.type == CompileFile.Type.AndroidManifest }?.file
        val result = incLinkCompile(apkFileUnit, aapt2Invoker, flatFiles, androidManifestFile, task.outputDir)

        if (result.isEmpty()) {
            // reload
            logger.debug("incLink failed, may effects later compilation. release and reinit next time call.")
            aapt2Invoker.release()

            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "makeResApk failed"))
                Result.failure(error)
            }, emptyList())
        }

        val javaFile = result.find { it.type == CompileOutput.Type.Java }?.file
        if (javaFile?.exists() == true) {
            rJavaFixer.fixIfNeeded(javaFile)
        }

        if (apkFileUnit.isBaseApk) {
            // base apk will always run first, because dynamic feature module depends on base apk
            isBaseApkArscUpdate = javaFile?.exists() == true
            baseApkUpdateFlatFiles = flatFiles
            if (isBaseApkArscUpdate) {
                logger.debug("base apk res.arsc updated, will update all dynamic feature modules ids")
            }
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

    private fun incLinkCompile(apkFileUnit: ApkFileUnit, aapt2Invoker: Aapt2DaemonInvoker, flatFiles: List<File>, androidManifest: File?, outputDir: File): List<CompileOutput> {
        val rFileDir = File(outputDir, apkFileUnit.getUniquePath("rjava"))
        val overlayDir = File(outputDir, apkFileUnit.getUniquePath("overlays"))
        rFileDir.mkdirs()
        overlayDir.mkdirs()

        val manifestName = androidManifest?.absolutePath ?: "no_need_compile_manifest"

        val command = mutableListOf(
            "inclink",
            "-o",
            overlayDir.absolutePath,
            "--output-to-dir",
            "--java",
            rFileDir.absolutePath,
            "--manifest",
            manifestName,
        )
        if (!context.isSingleApk && !apkFileUnit.isBaseApk) {
            // -R: Compilation unit to link, using `overlay` semantics. The last conflicting resource given takes precedence
            command.addAll(listOf(
                "--custom-package",
                apkFileUnit.resourcePackage,
                "--allow-reserved-package-id",
            ))
        }
        command.addAll(flatFiles.map { it.absolutePath })

        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            logger.error("aapt2 invoke failed, error msg: ${result.errorOutput}")
            return emptyList()
        }

        val rFiles = rFileDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Java, it, rFileDir)
        }
        val overlays = overlayDir.listFilesRecursively().map {
            CompileOutput(CompileOutput.Type.Res, it, overlayDir, apkFileUnit.apkFile.path)
        }

        // check whether resources has more config created. e.g. layout-v22
        return rFiles + overlays
    }

    override fun warmUp() {
        if (aapt2InvokerMap.isEmpty()) {
            // only preload the biggest apk
            val loadFirstApk = context.apkInfos.firstOrNull()?.baseApk
            if (loadFirstApk != null) {
                loadTable(loadFirstApk)
            }
        }
    }

    override fun dispose() {
        aapt2InvokerMap.values.toList().forEach {
            it.release()
        }
        aapt2InvokerMap.clear()
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
