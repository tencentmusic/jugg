package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.run.DeploymentService
import com.intellij.openapi.project.Project

/**
 * Android Studio Giraffe
 */
open class HedgehogAsDeployerCompat: GiraffeAsDeployerCompat() {

    override fun getDeploymentService(project: Project): DeploymentService {
        return DeploymentService.getInstance()
    }
}
