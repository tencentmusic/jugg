package com.sickworm.intellij.jugg.aapt2

/**
 * Aapt2Result stdout and stderr from a single aapt2 daemon command.
 * Collaboration: Created by [Aapt2DaemonInvoker.invoke] and [Aapt2DaemonInvoker.OutputReader.read], then consumed by compile/deploy callers.
 * Data Contract: [output] stores stdout, [errorOutput] stores stderr, and [isSuccess] returns false when stderr contains `error: `.
 */
class Aapt2Result(
    val output: String,
    val errorOutput: String,
) {
    val isSuccess: Boolean get() = !errorOutput.contains("error: ")
}
