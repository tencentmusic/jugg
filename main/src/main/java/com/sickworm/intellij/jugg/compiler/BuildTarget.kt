package com.sickworm.intellij.jugg.compiler

/**
 * BuildTarget tags the compile + launch strategy for a run session.
 *
 * APP    – compiles only the app variant, launches with am start.
 * ANDROID_TEST – compiles app + androidTest variants, launches with am instrument.
 */
enum class BuildTarget(
    /** Variant suffix appended to the Gradle task name, e.g. "" or "AndroidTest". */
    val variantSuffix: String,
    /** Whether the androidTest source set should be included in the compile context. */
    val includeAndroidTestSourceSet: Boolean,
    /** How to launch the app after deployment. */
    val launchStrategy: LaunchStrategy,
) {
    APP("", false, LaunchStrategy.AM_START),
    ANDROID_TEST("AndroidTest", true, LaunchStrategy.AM_INSTRUMENT);
}

/** LaunchStrategy determines how the deployed app is started on the device. */
enum class LaunchStrategy {
    AM_START,
    AM_INSTRUMENT,
}
