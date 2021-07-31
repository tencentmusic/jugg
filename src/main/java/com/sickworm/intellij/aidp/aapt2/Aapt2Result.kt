package com.sickworm.intellij.aidp.aapt2

class Aapt2Result(
    val output: String,
    val errorOutput: String,
) {
    val isSuccess: Boolean get() = errorOutput.isEmpty()
}