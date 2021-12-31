package com.sickworm.intellij.jugg.mock

import java.io.File

class ProjectInfo(
    projectRootDir: String,
    modifiedSourceDir: String,
    apkPath: String,
    val apkInfo: ApkInfo,
) {

    val projectRoot = File(projectRootDir).absoluteFile
    val apk = File(projectRoot, apkPath).absoluteFile
    val modifiedSource = File(modifiedSourceDir).absoluteFile

    companion object {
        val DEMO = ProjectInfo(
            projectRootDir = "src/test/assets/android/MyApplicationIntellij",
            modifiedSourceDir = "src/test/assets/android/modify_source",
            apkPath = "app/build/outputs/apk/debug/app-debug.apk",
            apkInfo = ApkInfo(
                classCount = 2387,
                fieldCount = 12293,
                methodCount = 19338,
                overlayFileCount = 756
            ),
        )

        val WESING = ProjectInfo(
            projectRootDir = "/Users/wormchen/IdeaProjects/wesing_international_android",
            modifiedSourceDir = "",
            apkPath = "app/build/outputs/apk/debug/app-debug.apk",
            apkInfo = ApkInfo(
                classCount = 2387,
                fieldCount = 12293,
                methodCount = 19338,
                overlayFileCount = 756
            ),
        )
    }

    class ApkInfo(
        val classCount: Int,
        val fieldCount: Int,
        val methodCount: Int,
        val overlayFileCount: Int
    )
}