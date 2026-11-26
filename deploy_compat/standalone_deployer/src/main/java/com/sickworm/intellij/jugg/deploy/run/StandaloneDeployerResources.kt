package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.Version
import com.sickworm.intellij.jugg.project.runtime.JuggResourceManager
import com.sickworm.intellij.jugg.project.runtime.PreparedRuntimeResource

/** Prepares the fixed Quail installer bundle and rejects installer protocol mismatches. */
object StandaloneDeployerResources {

    fun prepare(): PreparedRuntimeResource {
        val prepared = JuggResourceManager(
            classLoader = StandaloneDeployerResources::class.java.classLoader,
        ).prepare(resourceRoot = "deployer/quail")
        check(prepared.metadata.protocolVersion == Version.hash()) {
            "Standalone deployer protocol mismatch: Java=${Version.hash()}, installer=${prepared.metadata.protocolVersion}"
        }
        return prepared
    }
}
