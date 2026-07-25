package com.sickworm.jugg.demo.kmp

import com.sickworm.jugg.demo.kmp.generated.resources.Res
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_engines
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_font
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_icon
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_title
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_turns
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Exercises every common Compose resource accessor used by the fixture. */
@OptIn(ExperimentalResourceApi::class)
object KmpComposeResourceCase {
    val string = Res.string.baseline_title
    val array = Res.array.baseline_engines
    val plurals = Res.plurals.baseline_turns
    val drawable = Res.drawable.baseline_icon
    val font = Res.font.baseline_font
    suspend fun fileBytes(): ByteArray = Res.readBytes("files/baseline_payload.txt")
}
