package com.sickworm.intellij.jugg.deploy.run

import java.io.File

class AndroidRunConfig(
    val moduleName: String,
    val variants: List<Variant>,
    val signingConfigList: List<SigningConfig>,
)

class Variant(
    val name: String,
    val signingConfigName: String?,
)

class SigningConfig(
    val moduleName: String,
    val configName: String,
    val keystore: File?,
    val storePassword: String?,
    val keyAlias: String?,
) {
    val isInvalid: Boolean get() {
        return keystore == null || !keystore.exists() || storePassword == null
    }

    override fun toString(): String {
        return "${moduleName}($configName)"
    }
}