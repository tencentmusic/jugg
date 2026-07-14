package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState

/**
 * IdeaHostDeployStateResolver reads device deployment state from the active Android Studio runtime.
 */
class IdeaHostDeployStateResolver(
    private val project: Project,
) : IHostDeployStateResolver {

    override fun resolve(device: IDevice?, packageName: String?): IdeDeployState {
        return AsDeployerCompat.getIdeDeployStateResult(project, device, packageName)
    }
}
