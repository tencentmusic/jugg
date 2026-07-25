@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package com.sickworm.jugg.demo.kmp.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.PluralStringResource

private object CommonMainPlurals0 {
  public val baseline_turns: PluralStringResource by 
      lazy { init_baseline_turns() }
}

@InternalResourceApi
internal fun _collectCommonMainPlurals0Resources(map: MutableMap<String, PluralStringResource>) {
  map.put("baseline_turns", CommonMainPlurals0.baseline_turns)
}

public val Res.plurals.baseline_turns: PluralStringResource
  get() = CommonMainPlurals0.baseline_turns

private fun init_baseline_turns(): PluralStringResource =
    org.jetbrains.compose.resources.PluralStringResource(
  "plurals:baseline_turns", "baseline_turns",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/com.sickworm.jugg.demo.kmp.generated.resources/values/plurals.commonMain.cvr",
    10, 62),
    )
)
