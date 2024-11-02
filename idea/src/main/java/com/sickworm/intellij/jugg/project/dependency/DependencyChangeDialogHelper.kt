package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ConfirmResult
import com.sickworm.intellij.jugg.logger.getInstance

class DependencyChangeDialogHelper(loggerArg: Logger) {

    private val logger = loggerArg.getInstance("DependencyChangeDialogHelper")

    fun showChangeConfirmDialog(
        diffResult: DependencyDiffResult?,
        isRunLater: Boolean,
    ): ConfirmResult {
        if (diffResult == null) {
            logger.debug("show failed dialog")
            val confirmResult = CommonConfirmDialog.showAndGetOrCancel(
                title = "Jugg: Oops, Something Went Wrong",
                content = """<html>
                |<p>${"Oops, Something went wrong.".htmlWarning}</p>
                |<p>Jugg failed to find out changed libraries. Please report issues.</p>
                |</html>
                |""".trimMargin(),
                okButtonText = "Fallback to Gradle${if (isRunLater) " Later" else ""}",
                isShowCancelButton = false,
            )
            return if (confirmResult.isCanceled) {
                ConfirmResult.CANCEL
            } else {
                ConfirmResult.NEGATIVE
            }
        } else if (diffResult.hasChanges) {
            logger.debug("show change confirm dialog, newLibraries: ${diffResult.newLibraryDependencies}, " +
                    "removedLibraries: ${diffResult.removedLibraryDependencies}")
            val changeList = diffResult.toHtmlChangeList()
            val confirmResult = CommonConfirmDialog.showAndGetOrCancel(
                title = "Jugg: Hey! Found ${changeList.size} Libraries Changed",
                content = """<html>
                    |<p>Do you want to <b>incremental compile</b> these changed libraries?
                    |<ul>
                    |${changeList.joinToString("\n") { "<li>${it}</li>" }}
                    |</ul>
                    |${"Caution".htmlWarning}: This may cause unexpected build result. Please check changes carefully.
                    |<br> <br>
                    |</p>
                    |</html>
                    |""".trimMargin(),
                okButtonText = "Yes, Incremental Compile!",
                negativeButtonText = "Fallback to Gradle${if (isRunLater) " Later" else ""}",
            )
            return confirmResult
        } else {
            logger.debug("show no change confirm dialog")
            val confirmResult = CommonConfirmDialog.showAndGetOrCancel(
                title = "Jugg: Oops, No Library Changes Found",
                content = """<html>
                |<p>${"It seems no library changed.".htmlWarning}</p>
                |<p>Do you want to <b>ignore</b> build files changed?<br>
                |<b>Caution: This may cause unexpected build result.</b></p>
                |</html>
                |""".trimMargin(),
                okButtonText = "Ignore Build File Changes",
                negativeButtonText = "Fallback to Gradle${if (isRunLater) " Later" else ""}",
            )
            return confirmResult
        }
    }

}
