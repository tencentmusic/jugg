package com.sickworm.intellij.jugg.deploy.run.applychanges

/** Marks failures owned by the user-configured APK installation flow. */
class CustomApkInstallScriptException(message: String, cause: Throwable) : RuntimeException(message, cause)
