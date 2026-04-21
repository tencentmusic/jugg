package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo

/**
 * Sorts APKs for installation: non-test APKs (app / feature) are installed first,
 * followed by test APKs. The relative order within each group is preserved (stable).
 *
 * This guarantees that `am instrument`'s `targetPackage` is already on the device when
 * the test APK is installed, matching the behaviour expected by AGP and Android Studio.
 */
fun List<ApkInfo>.sortedForInstall(): List<ApkInfo> =
    sortedWith(compareBy { if (it.isTestApk) 1 else 0 })
