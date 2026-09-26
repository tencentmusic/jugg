package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.sickworm.intellij.jugg.deploy.run.DeployItem

data class NativeSandboxWriteRequest(
    val packageName: String,
    val sessionId: String,
    val files: List<DeployItem>,
    val apkNamesByPath: Map<String, String>,
)
