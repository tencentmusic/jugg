package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.gradle.compile.isChild
import java.io.File
import java.lang.reflect.Type

class ApkInfoSerializer {

    fun serialize(juggRootDir: File, apkInfos: List<ApkInfo>): String {
        val jsonData = apkInfos.map { ApkInfoJsonData.fromApkInfo(juggRootDir, it) }
        return GsonBuilder().setPrettyPrinting().create().toJson(jsonData)
    }

    fun deserialize(juggRootDir: File, json: String): List<ApkInfo> {
        val type: Type = object : TypeToken<List<ApkInfoJsonData>>() {}.type
        val apkInfos = Gson().fromJson(json, type) as List<ApkInfoJsonData>
        return apkInfos.map { it.toApkInfo(juggRootDir) }
    }

    private class ApkInfoJsonData(
        val files: List<ApkFileUnitJsonData>,
        val applicationId: String
    ) {

        fun toApkInfo(juggRootDir: File): ApkInfo {
            val files = files.map {
                val absoluteFile = if (File(it.apkFilePath).isAbsolute) {
                    File(it.apkFilePath)
                } else {
                    File(juggRootDir, it.apkFilePath)
                }
                ApkFileUnit(applicationId, it.moduleName, absoluteFile)
            }
            return ApkInfo(files, applicationId)
        }

        companion object {
            fun fromApkInfo(juggRootDir: File, apkInfo: ApkInfo): ApkInfoJsonData {
                return ApkInfoJsonData(
                    apkInfo.files.map {
                        val relativePathIfInRoot = if (it.apkFile.isChild(juggRootDir)) {
                            it.apkFile.relativeTo(juggRootDir).path
                        } else {
                            it.apkFile.path
                        }
                        ApkFileUnitJsonData(it.moduleName, relativePathIfInRoot)
                    },
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