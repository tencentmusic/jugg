package com.sickworm.jugg.demo.kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sickworm.jugg.demo.kmp.generated.resources.Res
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_engines
import com.sickworm.jugg.demo.kmp.generated.resources.baseline_turns
import com.sickworm.jugg.demo.kmp.generated.resources.custom_android_title
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun ExtendedComposeResourceSection() {
    var fileSummary by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        val bytes = Res.readBytes("files/baseline_payload.txt")
        fileSummary = "${bytes.decodeToString()} (${bytes.size} bytes, hash=${bytes.contentHashCode()})"
    }

    ResourceValue("String array", stringArrayResource(Res.array.baseline_engines).joinToString())
    ResourceValue("Plural one", pluralStringResource(Res.plurals.baseline_turns, 1, 1))
    ResourceValue("Plural other", pluralStringResource(Res.plurals.baseline_turns, 2, 2))
    ResourceValue("Raw file", fileSummary)
    ResourceValue("Android custom directory", stringResource(Res.string.custom_android_title))
}
