package com.sickworm.jugg.demo.kmp

import com.sickworm.jugg.demo.kmp.generated.resources.Res
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_font
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_icon
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_title
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Exercises the resource accessors shared by all supported Compose versions. */
@OptIn(ExperimentalResourceApi::class)
object KmpComposeResourceCase {
    val string = Res.string.baseline_title
    val drawable = Res.drawable.baseline_icon
    val font = Res.font.baseline_font
}
