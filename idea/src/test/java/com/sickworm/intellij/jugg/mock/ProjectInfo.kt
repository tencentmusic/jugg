package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.google.gson.Gson
import java.io.File
import kotlin.io.normalize

@Suppress("MemberVisibilityCanBePrivate")
class ProjectInfo(
    val packageName: String,
    val projectRootDir: String,
    val modifiedSourceDir: String,
    val apkPath: String,
    val apkEntryInfo: ApkEntryInfo,
) {

    val projectRoot: File get() = File(projectRootDir).absoluteFile.normalize()
    val apkFile: File get() = File(projectRoot, apkPath).absoluteFile.normalize()
    val modifiedSource: File get() = File(modifiedSourceDir).absoluteFile.normalize()
    val apkInfo: ApkInfo get() = ApkInfo(apkFile, androidApkPackage)
    val apkInfos: List<ApkInfo> get() = listOf(apkInfo)

    companion object {
        val DEMO_JSON = """
{
    "packageName": "com.example.myapplication",
    "projectRootDir": "../android_demo_project",
    "modifiedSourceDir": "src/test/assets/android/modify_source",
    "apkPath": "build/app/outputs/apk/debug/app-debug.apk",
    "apkEntryInfo": {
        "classCount": 2399,
        "fieldCount": 12300,
        "methodCount": 19370,
        "overlayFileCount": 756
    }
}
""".trimIndent()

        fun parseJson(json: String): ProjectInfo {
            return Gson().fromJson(json, ProjectInfo::class.java)
        }
    }

    class ApkEntryInfo(
        val classCount: Int,
        val fieldCount: Int,
        val methodCount: Int,
        val overlayFileCount: Int
    ) {

        val isNeedCheck get() = classCount > 0 || fieldCount > 0 || methodCount > 0 || overlayFileCount > 0
    }
}
