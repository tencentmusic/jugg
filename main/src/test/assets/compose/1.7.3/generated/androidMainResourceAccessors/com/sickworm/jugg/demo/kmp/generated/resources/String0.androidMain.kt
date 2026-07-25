@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.sickworm.jugg.demo.kmp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.StringResource

private object AndroidMainString0 {
  public val android_baseline_title: StringResource by 
      lazy { init_android_baseline_title() }
}

@InternalResourceApi
internal fun _collectAndroidMainString0Resources(map: MutableMap<String, StringResource>) {
  map.put("android_baseline_title", AndroidMainString0.android_baseline_title)
}

public val Res.string.android_baseline_title: StringResource
  get() = AndroidMainString0.android_baseline_title

private fun init_android_baseline_title(): StringResource =
    org.jetbrains.compose.resources.StringResource(
  "string:android_baseline_title", "android_baseline_title",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/android_strings.androidMain.cvr",
    10, 62),
    )
)
