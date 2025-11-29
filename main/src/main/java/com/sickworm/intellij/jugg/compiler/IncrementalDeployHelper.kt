package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkFileModifier
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.deployItemName
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.project.JuggException

/**
 * Extract from JuggDeployHelper and BuildIncrementalApkCommand.
 * Update contents to apk and resign.
 */
class IncrementalDeployHelper(private val context: ICompileContext, private val logger: Logger) {

    /**
     * @return <isSuccess, failedReason>
     */
    fun updateApk(apkInfos: List<ApkInfo>, outputs: List<CompileOutput>) : Pair<Boolean, String> {
        val allDeployItems = outputs.map {
            if (it.type == CompileOutput.Type.Dex) {
                // put in INCREMENTAL_DATA_PATH
                it.toDeployItem(deployName = INCREMENTAL_DATA_PATH + it.deployItemName + ".dex")
            } else {
                // override
                it.toDeployItem()
            }
        }
        return updateApk(apkInfos, allDeployItems)
    }

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
                val isBaseOutput = it.apkPath == DeployItem.FLAG_CLASS || it.apkPath == DeployItem.FLAG_BASE_APK
                if ((isBaseApk && isBaseOutput) || (it.apkPath == apkFile.path)) {
                    deployItems.add(it)
                }
            }
            logger.debug("Update apk: $apkFile\nDeploy items:\n${deployItems.joinToString("\n") { "    " + it.name }}\n")
            if (deployItems.isNotEmpty()) {
                deployItems.forEach {
                    modifier.addFile(it.name, it.content)
                }
                try {
                    modifier.insertAndResign()
                } catch (e: Exception) {
                    logger.warn("Update apk failed: ${apkFile.absolutePath}", e)
                    return false to "Update apk failed: ${apkFile.absolutePath}"
                }
            }
            logger.info("Update apk success, output: ${apkFile.absolutePath}")
        }
        return true to ""
    }

    companion object {
        private const val INCREMENTAL_DATA_PATH = "assets/jugg_/"
    }
}