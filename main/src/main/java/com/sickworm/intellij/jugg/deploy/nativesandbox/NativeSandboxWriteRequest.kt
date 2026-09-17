package com.sickworm.intellij.jugg.deploy.nativesandbox

import com.sickworm.intellij.jugg.deploy.run.DeployItem

data class NativeSandboxWriteRequest(
    val packageName: String,
    val sessionId: String,
    val abiDirs: Map<String, List<DeployItem>>,
)
