package com.sickworm.intellij.jugg.deploy.run

import java.io.File

class AndroidRunConfig(
    val signingConfigList: List<SigningConfig>,
)

class SigningConfig(
    val moduleName: String,
    val variantName: String,
    val keystore: File?,
    val storePassword: String?,
    val keyAlias: String?,
) {
    val isInvalid: Boolean get() {
        return keystore == null || !keystore.exists() || storePassword == null
    }

    override fun toString(): String {
        return "${moduleName}($variantName)"
    }
}