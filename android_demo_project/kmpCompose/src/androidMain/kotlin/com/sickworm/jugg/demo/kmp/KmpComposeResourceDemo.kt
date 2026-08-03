package com.sickworm.jugg.demo.kmp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sickworm.jugg.demo.kmp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Displays representative Compose resources for manual incremental compilation checks. */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun KmpComposeResourceDemo() {
    var fontSummary by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        val bytes = Res.readBytes("font/baseline_font.ttf")
        fontSummary = "${bytes.size} bytes, hash=${bytes.contentHashCode()}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F7FA))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        BasicText(
            text = "KMP Compose Resource Demo",
            style = TextStyle(color = Color(0xFF172033), fontSize = 22.sp),
        )
        Spacer(Modifier.height(24.dp))
        ResourceValue("Localized string", stringResource(KmpComposeResourceCase.string))
        ResourceValue("Android string", stringResource(KmpComposeAndroidResourceCase.string))
        ResourceValue("Font resource", fontSummary)
        Spacer(Modifier.height(16.dp))
        BasicText(
            text = "Density drawable",
            style = TextStyle(color = Color(0xFF5B6475), fontSize = 13.sp),
        )
        Spacer(Modifier.height(8.dp))
        Image(
            painter = painterResource(KmpComposeResourceCase.drawable),
            contentDescription = "Compose resource drawable",
            modifier = Modifier.size(120.dp),
        )
        Spacer(Modifier.height(24.dp))
        ExtendedComposeResourceSection()
    }
}

@Composable
internal fun ResourceValue(label: String, value: String) {
    BasicText(
        text = label,
        style = TextStyle(color = Color(0xFF5B6475), fontSize = 13.sp),
    )
    Spacer(Modifier.height(4.dp))
    BasicText(
        text = value,
        style = TextStyle(color = Color(0xFF172033), fontSize = 17.sp),
    )
    Spacer(Modifier.height(16.dp))
}
