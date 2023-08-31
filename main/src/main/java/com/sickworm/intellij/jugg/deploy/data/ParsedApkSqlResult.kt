package com.sickworm.intellij.jugg.deploy.data

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.apkInfoKey


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

    override fun toString(): String {
        return "ParsedApkDiffResult(apkInfo=${apkInfo.apkInfoKey}, updatedApkInfos=$updatedApkInfos, " +
                "addedOverlayFiles=${addedOverlayFiles.size}, removedOverlayFiles=${removedOverlayFiles.size}, " +
                "updatedOverlayFiles=${updatedOverlayFiles.size}, addedDexFiles=${addedDexFiles.size}, " +
                "removedDexFiles=${removedDexFiles.size}, updatedDexFiles=${updatedDexFiles.size}" +
                ")"
    }

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

    override fun toString(): String {
        return "ParsedApkUpdateResult(isSuccess=$isSuccess, errorMessage=$errorMessage, diffResult=$diffResult, " +
                "addedClasses=${addedClasses.size}, removedClasses=${removedClasses.size}, updatedClasses=${updatedClasses.size})"
    }

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