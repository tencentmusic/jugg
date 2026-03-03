package com.sickworm.jugg.demo.testcase.annotation.kotlin

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class ParcelizeData2(
    val argBoolean: Boolean,
    val argString: String?,
) : Parcelable