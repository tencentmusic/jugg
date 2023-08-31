package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo


data class ParsedApkDiffResult(
    val apkInfo: ApkInfo,

    val updatedApkInfos: Int = 0,

    val addedOverlayFiles: Map<String, JuggFileInfo> = emptyMap(),
    val removedOverlayFiles: Map<String, JuggFileInfo> = emptyMap(),
    val updatedOverlayFiles: Map<String, JuggFileInfo> = emptyMap(),

    val addedDexFiles: Map<String, JuggFileInfo> = emptyMap(),
    val removedDexFiles: Map<String, JuggFileInfo> = emptyMap(),
    val updatedDexFiles: Map<String, JuggFileInfo> = emptyMap(),
) {

    val includeEntries: ApkEntries get() {
        return ApkEntries(
            apkInfo,
            (addedDexFiles + updatedDexFiles),
            (addedOverlayFiles + updatedOverlayFiles)
        )
    }
}

data class ParsedApkUpdateResult(
    val isSuccess: Boolean,
    val errorMessage: String?,

    val diffResult: ParsedApkDiffResult?,

    val addedClasses: List<String>,
    val removedClasses: List<String>,
    val updatedClasses: List<String>,
) {

    companion object {

        fun success(diffResult: ParsedApkDiffResult): ParsedApkUpdateResult {
            return ParsedApkUpdateResult(
                isSuccess = true,
                errorMessage = null,
                diffResult = diffResult,
                addedClasses = emptyList(),
                removedClasses = emptyList(),
                updatedClasses = emptyList(),
            )
        }

        fun failed(diffResult: ParsedApkDiffResult?, errorMessage: String?): ParsedApkUpdateResult {
            return ParsedApkUpdateResult(
                isSuccess = false,
                errorMessage = errorMessage,
                diffResult = diffResult,
                addedClasses = emptyList(),
                removedClasses = emptyList(),
                updatedClasses = emptyList(),
            )
        }
    }
}