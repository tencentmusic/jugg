package com.sickworm.intellij.jugg.aapt2

/**
 * ApkResInfo model for parsed APK resource table metadata.
 */
data class ApkResInfo(
    val packageName: String,
    val id: Int,
    val groupList: List<ResGroup>
)

/**
 * ResGroup one resource-type group (for example drawable/string) inside [ApkResInfo].
 */
data class ResGroup(
    val type: String,
    val id: Int,
    val entryCount: Int,
    val itemList: List<ResId>
)

/**
 * ResId one logical resource entry (type/name/id) and all of its qualifiers.
 */
data class ResId(
    val type: String,
    val id: Int,
    val name: String,
    val resList: List<ResItem>
)

/**
 * ResItem one concrete resource file variant in [ResId.resList].
 */
data class ResItem(
    val prefix: String,
    val type: String,
    val filePath: String,
    val fileType: String,
)
