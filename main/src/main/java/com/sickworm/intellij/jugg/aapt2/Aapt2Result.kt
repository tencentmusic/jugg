package com.sickworm.intellij.jugg.aapt2

class Aapt2Result(
    val output: String,
    val errorOutput: String,
) {
    val isSuccess: Boolean get() = !errorOutput.contains("error: ")
}