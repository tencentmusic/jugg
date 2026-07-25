package com.sickworm.jugg.demo.kmp

expect object SharedPlatformLabel {
    fun value(): String
}

fun sharedPlatformLabel(): String = SharedPlatformLabel.value()
