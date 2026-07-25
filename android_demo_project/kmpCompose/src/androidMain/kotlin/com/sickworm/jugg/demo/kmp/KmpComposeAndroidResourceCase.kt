package com.sickworm.jugg.demo.kmp

import com.sickworm.jugg.demo.kmp.generated.resources.Res
import com.sickworm.jugg.demo.kmp.generated.resources.android_baseline_title
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString

/** Exercises the accessor generated from the Android custom resource directory. */
@OptIn(ExperimentalResourceApi::class)
object KmpComposeAndroidResourceCase {
    val string = Res.string.android_baseline_title

    suspend fun runtimeSnapshot(): String = listOf(
        getString(KmpComposeResourceCase.string),
        getString(string),
    ).joinToString("|")
}
