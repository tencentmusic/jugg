package com.sickworm.intellij.jugg.deploy.run.deployflow

/**
 * Placeholder for L2 cases that assert on-device overlay directories; run with `-Ddeploy.flow.device=real`.
 */
object DeployFlowRealDeviceBackend : DeployFlowDeviceBackend {

    override val mode: DeployFlowDeviceMode = DeployFlowDeviceMode.REAL

    override fun buildFixture(caseId: DeployFlowCaseId): DeployFlowFixture {
        throw UnsupportedOperationException(
            "DeployFlow real device backend is not implemented yet for $caseId; use mock backend in CI.",
        )
    }
}
