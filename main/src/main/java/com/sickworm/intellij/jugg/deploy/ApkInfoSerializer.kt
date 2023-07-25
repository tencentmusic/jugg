package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkFileUnit
import com.android.tools.idea.run.ApkInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

class ApkInfoSerializer {

    fun serialize(apkInfos: List<ApkInfo>): String {
        val jsonData = apkInfos.map { ApkInfoJsonData.fromApkInfo(it) }
        return GsonBuilder().setPrettyPrinting().create().toJson(jsonData)
    }

    fun deserialize(json: String): List<ApkInfo> {
        val type: Type = object : TypeToken<List<ApkInfoJsonData>>() {}.type
        val apkInfos = Gson().fromJson(json, type) as List<ApkInfoJsonData>
        return apkInfos.map { it.toApkInfo() }
    }

    private class ApkInfoJsonData(
        val files: List<ApkFileUnitJsonData>,
        val applicationId: String
    ) {

        fun toApkInfo(): ApkInfo {
            return ApkInfo(files.map { ApkFileUnit(it.moduleName, File(it.apkFilePath)) }, applicationId)
        }

        companion object {
            fun fromApkInfo(apkInfo: ApkInfo): ApkInfoJsonData {
                return ApkInfoJsonData(
                    apkInfo.files.map { ApkFileUnitJsonData(it.moduleName, it.apkFile.absolutePath) },
                    apkInfo.applicationId,
                )
            }
        }
    }

    private class ApkFileUnitJsonData(
        val moduleName: String,
        val apkFilePath: String,
    )
}