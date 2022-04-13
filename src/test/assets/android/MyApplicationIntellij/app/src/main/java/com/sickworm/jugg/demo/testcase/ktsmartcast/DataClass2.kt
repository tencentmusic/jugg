package com.sickworm.jugg.demo.testcase.ktsmartcast

data class DataClass2(
    val dataList: MutableList<String>? = null,
    val index: Int = 0,
    val nextIndex: Int = 0,
    val hasMore: Boolean = false,
    val errMsg: String? = null
)
