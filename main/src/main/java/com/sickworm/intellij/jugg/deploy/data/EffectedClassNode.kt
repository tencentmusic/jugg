package com.sickworm.intellij.jugg.deploy.data

data class EffectedClassNode(
    val className: String,
    val sourceFileName: String,
    val effectedByClasses: List<String>,
) {
    companion object {
        const val SOURCE_NOT_FOUND = "source_not_found"
    }
}