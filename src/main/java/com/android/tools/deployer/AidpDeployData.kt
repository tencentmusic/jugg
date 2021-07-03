package com.android.tools.deployer

import com.android.tools.deployer.model.DexClass

data class AidpDeployData(
    // TODO use custom class instead of DexClass
    val classes: List<DexClass>,
) {
    val isEmpty get() = classes.isEmpty()
}