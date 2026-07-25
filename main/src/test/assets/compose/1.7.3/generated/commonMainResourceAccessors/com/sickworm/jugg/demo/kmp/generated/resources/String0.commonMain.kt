@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.sickworm.jugg.demo.kmp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.StringResource

private object CommonMainString0 {
  public val baseline_title: StringResource by 
      lazy { init_baseline_title() }
}

@InternalResourceApi
internal fun _collectCommonMainString0Resources(map: MutableMap<String, StringResource>) {
  map.put("baseline_title", CommonMainString0.baseline_title)
}

public val Res.string.baseline_title: StringResource
  get() = CommonMainString0.baseline_title

private fun init_baseline_title(): StringResource = org.jetbrains.compose.resources.StringResource(
  "string:baseline_title", "baseline_title",
    setOf(
     
    org.jetbrains.compose.resources.ResourceItem(setOf(org.jetbrains.compose.resources.LanguageQualifier("zh"),
    org.jetbrains.compose.resources.RegionQualifier("CN"), ),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values-zh-rCN/strings.commonMain.cvr",
    10, 38),
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/strings.commonMain.cvr",
    10, 42),
    )
)
