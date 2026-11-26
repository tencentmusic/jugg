package com.sickworm.intellij.jugg.deploy.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.IncrementalDeployHelper
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.ide.ui.CommonErrorDialog
import com.sickworm.intellij.jugg.ide.ui.DirectorySelector
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import java.io.File

class ExportIncrementalApkHelper(
    private val project: Project,
    private val taskRunnerManager: TaskRunnerManager,
    private val deployFileManager: DeployFileManager,
    private val logger: Logger,
) {

    fun exportIncrementalApk(dialog: DialogWrapper, compileContext: ICompileContext) {
        logger.debug("exportIncrementalApk")

        if (!checkUncompiledFiles()) {
            dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
            return
        }

        val outputDir = DirectorySelector(project).selectDirectory()
        logger.debug("exportIncrementalApk to $outputDir")
        if (outputDir == null || !outputDir.isDirectory) {
            return
        }

        dialog.close(DialogWrapper.CLOSE_EXIT_CODE)
        taskRunnerManager.runTaskSafe("Export incremental compile", {
            doExportIncrementalApk(outputDir, compileContext)
        })
    }

    private fun checkUncompiledFiles(): Boolean {
        val uncompiledFiles = deployFileManager.getUncompiledFiles()
        if (uncompiledFiles.isNotEmpty()) {
            val files = uncompiledFiles.joinToString("\n") {
                it.file.relativeTo(File(project.basePath ?: "")).path
            }
            showErrorDialog("Not all files are compiled:\n$files\n\nPlease compile them first.")
            return false
        }
        return true
    }

    private fun doExportIncrementalApk(outputDir: File, compileContext: ICompileContext) {
        val deployItems = (deployFileManager.getDeployedFiles() + deployFileManager.getStagingFiles())
            .map { it.toDeployItem() }
        logger.debug("doExportIncrementalApk deployedFiles: ${deployFileManager.getDeployedFiles()}, " +
                "stagingFiles: ${deployFileManager.getStagingFiles()}")

        val result = IncrementalDeployHelper(compileContext, logger).exportIncrementalApk(outputDir, deployItems)
        logger.info("Export incremental apk result: $result")
        if (!result.isSuccess) {
            showErrorDialog("Export incremental apk failed: ${result.failedReason}")
            return
        }
        DirectorySelector(project).openDirectoryInFileManager(outputDir)
    }

    private fun showErrorDialog(message: String) {
        CommonErrorDialog.show(project, "Export Incremental APK failed", message)
    }
}