package com.sickworm.intellij.jugg.deploy.data

import java.io.File


data class ParsedApkDiffResult(
    val apkFile: File,

    val updatedApkInfos: Int = 0,

    val addedOverlayFiles: Map<String, JuggFileInfo> = emptyMap(),
    val removedOverlayFiles: Map<String, JuggFileInfo> = emptyMap(),
    val updatedOverlayFiles: Map<String, JuggFileInfo> = emptyMap(),

    val addedDexFiles: Map<String, JuggFileInfo> = emptyMap(),
    val removedDexFiles: Map<String, JuggFileInfo> = emptyMap(),
    val updatedDexFiles: Map<String, JuggFileInfo> = emptyMap(),

    val isFullUpdate: Boolean = false,
) {

    constructor(apkEntries: ApkEntries) : this(
        apkFile = apkEntries.apkFile,
        updatedApkInfos = 1,
        addedOverlayFiles = apkEntries.overlayFiles,
        removedOverlayFiles = emptyMap(),
        updatedOverlayFiles = emptyMap(),
        addedDexFiles = apkEntries.dexFiles,
        removedDexFiles = emptyMap(),
        updatedDexFiles = emptyMap(),
        isFullUpdate = true,
    )

    override fun toString(): String {
        return "ParsedApkDiffResult(apkFile=$apkFile, updatedApkInfos=$updatedApkInfos, " +
                "addedOverlayFiles=${addedOverlayFiles.size}, removedOverlayFiles=${removedOverlayFiles.size}, " +
                "updatedOverlayFiles=${updatedOverlayFiles.size}, addedDexFiles=${addedDexFiles.size}, " +
                "removedDexFiles=${removedDexFiles.size}, updatedDexFiles=${updatedDexFiles.size}" +
                ")"
    }

    val includeEntries: ApkEntries get() {
        return ApkEntries(
            apkFile,
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