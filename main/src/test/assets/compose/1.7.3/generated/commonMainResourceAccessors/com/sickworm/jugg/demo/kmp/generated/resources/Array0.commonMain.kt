@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.sickworm.jugg.demo.kmp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.StringArrayResource

private object CommonMainArray0 {
  public val baseline_engines: StringArrayResource by 
      lazy { init_baseline_engines() }
}

@InternalResourceApi
internal fun _collectCommonMainArray0Resources(map: MutableMap<String, StringArrayResource>) {
  map.put("baseline_engines", CommonMainArray0.baseline_engines)
}

public val Res.array.baseline_engines: StringArrayResource
  get() = CommonMainArray0.baseline_engines

private fun init_baseline_engines(): StringArrayResource =
    org.jetbrains.compose.resources.StringArrayResource(
  "string-array:baseline_engines", "baseline_engines",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/arrays.commonMain.cvr",
    10, 47),
    )
)
