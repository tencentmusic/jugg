package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.run.ApkInfo
import com.google.gson.Gson
import java.io.File

@Suppress("MemberVisibilityCanBePrivate")
class ProjectInfo(
    val packageName: String,
    val projectRootDir: String,
    val modifiedSourceDir: String,
    val apkPath: String,
    val apkEntryInfo: ApkEntryInfo,
) {

    val projectRoot: File get() = File(projectRootDir).absoluteFile
    val apkFile: File get() = File(projectRoot, apkPath).absoluteFile
    val modifiedSource: File get() = File(modifiedSourceDir).absoluteFile
    val apkInfo: ApkInfo get() = ApkInfo(apkFile, androidApkPackage)

    companion object {
        val DEMO_JSON = """
{
    "packageName": "com.example.myapplication",
    "projectRootDir": "src/test/assets/android/MyApplicationIntellij",
    "modifiedSourceDir": "src/test/assets/android/modify_source",
    "apkPath": "app/build/outputs/apk/debug/app-debug.apk",
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