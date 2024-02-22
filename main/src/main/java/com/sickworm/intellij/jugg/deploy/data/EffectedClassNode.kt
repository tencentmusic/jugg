package com.sickworm.intellij.jugg.deploy.data

data class EffectedClassNode(
    val className: String,
    val sourceFileName: String,
    val effectedByClasses: List<String>,
)