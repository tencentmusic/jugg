package com.sickworm.intellij.jugg.deploy.run.deployflow

/**
 * Selects mock vs real device preparation for the same [DeployFlowCaseId].
 */
enum class DeployFlowDeviceMode {
    MOCK,
    REAL,
}

interface DeployFlowDeviceBackend {
    val mode: DeployFlowDeviceMode
    fun buildFixture(caseId: DeployFlowCaseId): DeployFlowFixture
}

fun resolveDeployFlowDeviceBackend(): DeployFlowDeviceBackend {
    return when (System.getProperty("deploy.flow.device", "mock").lowercase()) {
        "real" -> DeployFlowRealDeviceBackend
        else -> DeployFlowMockBackend
    }
}
