package com.sickworm.jugg.demo.testcase.annotation.kotlin

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ParcelizeData(
    val argBoolean: Boolean,
    val argString: String?,
) : Parcelable