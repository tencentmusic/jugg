package com.sickworm.intellij.jugg.deploy.run

/**
 * The state of deployment which detected by IDE. Use to check why we can't deploy.
 */
data class IdeDeployState(
    val state: State,
    val message: String,
) {

    enum class State {
        OK,
        NO_ANDROID_CONFIGURATION,
        NO_DEPLOYMENT_PROVIDER,
        NO_DEVICE,
        INVALID_DEVICE,
        NO_DEPLOYABLE_APP,
        INTERNAL_ERROR,
    }

    companion object {

        val ok = IdeDeployState(
            State.OK,
            "ready to deploy"
        )

        val noAndroidConfiguration = IdeDeployState(
            State.NO_ANDROID_CONFIGURATION,
            "no available supported Android configuration"
        )

        val noDeploymentProvider = IdeDeployState(
            State.NO_DEPLOYMENT_PROVIDER,
            "no deployment provider",
        )

        val selectDeviceIsInvalid = IdeDeployState(
            State.NO_DEVICE,
            "selected device is invalid",
        )

        val deviceNotAuthorized = IdeDeployState(
            State.NO_DEVICE,
            "device not authorized",
        )

        val deviceNotConnected = IdeDeployState(
            State.NO_DEVICE,
            "device not connected",
        )

        val unknownDeviceApiLevel = IdeDeployState(
            State.INVALID_DEVICE,
            "unknown device API level",
        )

        val incompatibleDeviceApiLevel = IdeDeployState(
            State.INVALID_DEVICE,
            "device API level lower than ${IAsDeployerCompat.MIN_DEVICE_API}",
        )

        val appNotRunningOrNotDebuggable = IdeDeployState(
            State.NO_DEPLOYABLE_APP,
            "app not running or not debuggable",
        )

        val updateInterrupted = IdeDeployState(
            State.INTERNAL_ERROR,
            "update interrupted",
        )

        val unexpectedException = IdeDeployState(
            State.INTERNAL_ERROR,
            "unexpected exception",
        )

        val unsupportedExecutionTarget = IdeDeployState(
            State.INVALID_DEVICE,
            "unsupported execution target",
        )

        val canNotDetectApplicationId = IdeDeployState(
            State.NO_DEPLOYABLE_APP,
            "can't detect applicationId",
        )
    }
}