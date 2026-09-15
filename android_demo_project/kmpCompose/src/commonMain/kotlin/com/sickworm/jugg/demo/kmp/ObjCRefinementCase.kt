package com.sickworm.jugg.demo.kmp

import kotlin.native.HiddenFromObjC

/**
 * Fixture for the typed `compilerOptions.optIn` contract on the common source path.
 *
 * The declaration deliberately has no local `@OptIn`, so it only compiles when the toolchain
 * forwards the module level `-opt-in=kotlin.experimental.ExperimentalObjCRefinement` argument that
 * Kotlin 2.x keeps in the typed `compilerOptions.optIn` instead of `freeCompilerArgs`.
 *
 * `@HiddenFromObjC` is an `@OptionalExpectation` annotation, so the declaration is only accepted
 * when the file is compiled as a common source. Keep the expect declaration below in this file: it
 * is what makes an incremental compile of a single changed file go through the KMP common path.
 */
@HiddenFromObjC
fun objcRefinementBaseline(): String = "objc-baseline:${objcRefinementPlatform()}"

expect fun objcRefinementPlatform(): String
