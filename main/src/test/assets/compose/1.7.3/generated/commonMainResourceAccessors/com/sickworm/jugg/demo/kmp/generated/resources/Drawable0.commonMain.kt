@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.sickworm.jugg.demo.kmp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi

private object CommonMainDrawable0 {
  public val baseline_icon: DrawableResource by 
      lazy { init_baseline_icon() }
}

@InternalResourceApi
internal fun _collectCommonMainDrawable0Resources(map: MutableMap<String, DrawableResource>) {
  map.put("baseline_icon", CommonMainDrawable0.baseline_icon)
}

public val Res.drawable.baseline_icon: DrawableResource
  get() = CommonMainDrawable0.baseline_icon

private fun init_baseline_icon(): DrawableResource =
    org.jetbrains.compose.resources.DrawableResource(
  "drawable:baseline_icon",
    setOf(
     
    org.jetbrains.compose.resources.ResourceItem(setOf(org.jetbrains.compose.resources.DensityQualifier.HDPI,
    ),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/drawable-hdpi/baseline_icon.png", -1, -1),
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/drawable/baseline_icon.png", -1, -1),
    )
)
