@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.sickworm.jugg.demo.kmp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.InternalResourceApi

private object CommonMainFont0 {
  public val baseline_font: FontResource by 
      lazy { init_baseline_font() }
}

@InternalResourceApi
internal fun _collectCommonMainFont0Resources(map: MutableMap<String, FontResource>) {
  map.put("baseline_font", CommonMainFont0.baseline_font)
}

public val Res.font.baseline_font: FontResource
  get() = CommonMainFont0.baseline_font

private fun init_baseline_font(): FontResource = org.jetbrains.compose.resources.FontResource(
  "font:baseline_font",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/font/baseline_font.ttf", -1, -1),
    )
)
