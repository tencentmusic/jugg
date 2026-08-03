package com.sickworm.jugg.demo.testcase.kmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sickworm.jugg.demo.kmp.KmpComposeResourceDemo

/** Hosts the KMP Compose resource page used for manual compilation checks. */
class KmpComposeResourceDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KmpComposeResourceDemo()
        }
    }
}
