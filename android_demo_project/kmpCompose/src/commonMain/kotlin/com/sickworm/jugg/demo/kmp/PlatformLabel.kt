package com.sickworm.jugg.demo.kmp

expect object PlatformLabel {
    fun value(): String
}

fun platformLabel(): String = PlatformLabel.value()

fun platformMarker(): String = "${baselineCommonPrefix()}:baseline"
