package com.sickworm.intellij.jugg.ide.ui

import com.android.tools.idea.util.toIoFile
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.io.File

class DirectorySelector(
    private val project: Project,
    private val title: String = "Select Directory",
    private val description: String = "Select Directory",
    private val initialDirectory: File? = null
) {

    fun selectDirectory(): File? {
        val descriptor = FileChooserDescriptor(
            false,
            true,
            false,
            false,
            false,
            false).also {
            it.title = title
            it.description = description
        }

        val initialDir = initialDirectory?.let {
            LocalFileSystem.getInstance().findFileByIoFile(it)
        }
        val selectedFiles = FileChooser.chooseFiles(descriptor, project, initialDir)

        return selectedFiles.firstOrNull()?.let { virtualFile ->
            File(virtualFile.path)
        }
    }

    /**
     * Open the directory in the system file manager (alternative approach).
     *
     * @param directory The directory to open
     */
    fun openDirectoryInFileManager(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            showErrorDialog("Directory does not exist or is not a valid directory: ${directory.absolutePath}")
            return
        }

        ApplicationManager.getApplication().invokeLater({
            try {
                val directoryVirtualFile = LocalFileSystem.getInstance().findFileByIoFile(directory)
                if (directoryVirtualFile != null) {
                    // Open directory in system file manager
                    RevealFileAction.openDirectory(directoryVirtualFile.toIoFile())
                }
            } catch (e: Exception) {
                showErrorDialog("Error occurred while opening directory: ${e.message}")
            }
        }, ModalityState.defaultModalityState())
    }

    private fun showErrorDialog(message: String) {
        CommonErrorDialog.show(project, "Open directory Failed", message)
    }
}
