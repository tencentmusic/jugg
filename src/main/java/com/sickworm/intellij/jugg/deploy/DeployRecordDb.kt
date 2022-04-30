package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import java.io.File

/**
 * record source file status on deploy to ensure current correct
 */
class DeployRecordDb(dbFile: File) {

    val baseDeployRecord: BaseDeployRecord? = null
    val incDeployRecord: List<IncDeployRecord> = emptyList()

    fun onStartBuildApk() {
        // record git status
    }

    fun onBuildApk(apks: List<ApkInfo>) {
        // update apks
    }

    fun onIncDeployFinish(order: Int, compileOutputs: List<CompileOutput>) {
        // update deploy files
    }
}

data class BaseDeployRecord(
    val apks: List<ApkRecord>,
    val commitId: String,
    val changedFiles: List<FileRecord>
)

data class IncDeployRecord(
    val order: Int,
    val items: List<CompileRecord>,
    val commitId: String,
    val changedFiles: List<FileRecord>
)

data class ApkRecord(
    val path: String,
    val applicationId: String,
    val modifiedTime: Long
)

data class FileRecord(
    val path: String,
    val modifiedTime: Long
)

data class CompileRecord(
    val compileOutput: CompileOutput,
    val modifiedTime: Long
)