package com.sickworm.intellij.jugg.mock

import com.google.gson.Gson
import java.io.File

class ProjectInfo(
    val packageName: String,
    val projectRootDir: String,
    val modifiedSourceDir: String,
    val apkPath: String,
    val apkInfo: ApkInfo,
) {

    val projectRoot get() = File(projectRootDir).absoluteFile
    val apk get() = File(projectRoot, apkPath).absoluteFile
    val modifiedSource get() = File(modifiedSourceDir).absoluteFile

    companion object {
        val DEMO_JSON = """
{
    "packageName": "com.example.myapplication",
    "projectRootDir": "src/test/assets/android/MyApplicationIntellij",
    "modifiedSourceDir": "src/test/assets/android/modify_source",
    "apkPath": "app/build/outputs/apk/debug/app-debug.apk",
    "apkInfo": {
        classCount": 2387,
        fieldCount": 12293,
        methodCount": 19338,
        overlayFileCount": 756
    }
}
""".trimIndent()

        fun parseJson(json: String): ProjectInfo {
            return Gson().fromJson(json, ProjectInfo::class.java)
        }
    }

    class ApkInfo(
        val classCount: Int,
        val fieldCount: Int,
        val methodCount: Int,
        val overlayFileCount: Int
    )
}