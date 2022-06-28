package com.sickworm.intellij.jugg.aapt2

data class ApkResInfo(
    val packageName: String,
    val id: Int,
    val groupList: List<ResGroup>
)

data class ResGroup(
    val type: String,
    val id: Int,
    val entryCount: Int,
    val itemList: List<ResId>
)

data class ResId(
    val type: String,
    val id: Int,
    val name: String,
    val resList: List<ResItem>
)

data class ResItem(
    val prefix: String,
    val type: String,
    val filePath: String,
    val fileType: String,
)