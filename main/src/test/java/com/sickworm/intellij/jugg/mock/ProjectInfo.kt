package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.google.gson.Gson
import java.io.File
import kotlin.io.normalize

@Suppress("MemberVisibilityCanBePrivate")
class ProjectInfo(
    val packageName: String,
    val projectRootDir: String,
    val apkPath: String,
) {

    val projectRoot: File get() = File(projectRootDir).absoluteFile.normalize()
    val apkFile: File get() = File(projectRoot, apkPath).absoluteFile.normalize()
    val apkInfo: ApkInfo get() = ApkInfo(apkFile, packageName)
    val apkInfos: List<ApkInfo> get() = listOf(apkInfo)

    companion object {
        val DEMO_JSON = """
{
    "packageName": "com.example.myapplication",
    "projectRootDir": "../android_demo_project",
    "apkPath": "build/app/outputs/apk/debug/app-debug.apk"
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
