package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File

/**
 * Extract from JuggDeployHelper and BuildIncrementalApkCommand.
 * Update contents to apk and resign.
 */
class IncrementalDeployHelper(private val context: ICompileContext, private val logger: Logger) {

    /**
     * @return <isSuccess, failedReason>
     */
    fun updateApk(apkInfos: List<ApkInfo>, allDeployItems: List<DeployItem>): Pair<Boolean, String> {
        val baseApk = apkInfos.firstOrNull { it.baseApk != null }?.baseApk?.apkFile
        val allApks = apkInfos.flatMap { it.files }.map { it.apkFile }
        if (baseApk == null) {
            throw JuggException.baseApkNotFound(context.packageName, context.apkInfos)
        }

        val signingConfig = context.signingConfig
        if (signingConfig == null || signingConfig.isInvalid) {
            return false to "Unable to update APK, signing config not found."
        }

        allApks.forEach { apkFile ->
            val isBaseApk = apkFile == baseApk
            val modifier = ApkFileModifier(apkFile, signingConfig, context.androidHome, logger, context.cmdCompileEnv)
            val deployItems = mutableListOf<DeployItem>()
            allDeployItems.forEach {
                if (it.belongsTo(apkFile.path) || (isBaseApk && it.apkPath == DeployItem.FLAG_BASE_APK)) {
                    deployItems.add(it)
                }
            }
            logger.debug("Update apk: $apkFile\nDeploy items:\n${deployItems.joinToString("\n") { "    " + it.name }}\n")
            deployItems.forEach {
                if (it.type == CompileOutput.Type.Dex) {
                    // put in INCREMENTAL_DATA_PATH
                    val path = INCREMENTAL_DATA_PATH + it.name + ".dex"
                    modifier.addFile(path, it.content)
                } else {
                    // override
                    val path = it.name
                    modifier.addFile(path, it.content)
                }
            }
            try {
                modifier.insertAndResign()
            } catch (e: Exception) {
                logger.warn("Update apk failed: ${apkFile.absolutePath}", e)
                return false to "Update apk failed: ${apkFile.absolutePath}"
            }
            logger.info("Update apk success, output: ${apkFile.absolutePath}")
        }
        return true to ""
    }

    fun exportIncrementalApk(outputDir: File, deployItems : List<DeployItem>): ExportIncrementalApkResult {
        try {
            return doExportIncrementalApk(outputDir, deployItems)
        } catch (e: Exception) {
            logger.warn("Export incremental apk failed", e)
            return ExportIncrementalApkResult(
                isSuccess = false,
                apkFiles = emptyList(),
                failedReason = "Unexcepted error: $e",
            )
        }
    }

    private fun doExportIncrementalApk(outputDir: File, deployItems : List<DeployItem>): ExportIncrementalApkResult {
        val tempPrefix = ".tmp_"
        fun mapToTempApkFile(apkFile: File): File {
            val apkName = tempPrefix + "jugg_inc_" + apkFile.name
            return File(outputDir, apkName)
        }
        fun mapApkInfos(apkInfos: List<ApkInfo>, apkFileMapper: (File) -> File): List<ApkInfo> {
            return apkInfos.map { apkInfo ->
                ApkInfo(apkInfo.files.map {
                    ApkFileUnit(it.applicationId, it.moduleName, it.debuggable, apkFileMapper(it.apkFile))
                }, apkInfo.applicationId, apkInfo.instrumentationTargetPackage, apkInfo.instrumentationRunner)
            }
        }

        val apkInfos = mapApkInfos(context.apkInfos) { originApkFile ->
            val tempApkFile = mapToTempApkFile(originApkFile)
            originApkFile.copyTo(tempApkFile, overwrite = true)
            tempApkFile
        }
        val tempDeployItems = deployItems.map {
            val tempApkPath = if (File(it.apkPath).exists()) {
                mapToTempApkFile(File(it.apkPath)).path
            } else {
                it.apkPath
            }
            val tempTargetApkPaths = it.targetApkPaths.map { targetPath ->
                if (File(targetPath).exists()) {
                    mapToTempApkFile(File(targetPath)).path
                } else {
                    targetPath
                }
            }
            return@map DeployItem(it.name, it.type, it.checksum, it.content, tempApkPath, tempTargetApkPaths)
        }

        val (isSuccess, failedReason) = updateApk(apkInfos, tempDeployItems)
        if (!isSuccess) {
            return ExportIncrementalApkResult(
                isSuccess = false,
                apkFiles = emptyList(),
                failedReason = failedReason,
            )
        }

        val finalApkInfos = mapApkInfos(apkInfos) { tempApkFile ->
            val apkName = tempApkFile.name.substringAfter(tempPrefix)
            val finalApkFile = File(outputDir, apkName)
            finalApkFile.delete()
            tempApkFile.renameTo(finalApkFile)
            finalApkFile
        }
        val apkFiles = finalApkInfos.flatMap { apkInfo -> apkInfo.files.map { it.apkFile } }
        return ExportIncrementalApkResult(true, apkFiles, "")
    }

    companion object {
        const val INCREMENTAL_DATA_PATH = "assets/jugg_/"
    }
}
