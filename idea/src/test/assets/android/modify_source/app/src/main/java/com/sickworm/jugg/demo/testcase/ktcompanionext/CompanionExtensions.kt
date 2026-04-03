package com.sickworm.jugg.demo.testcase.ktcompanionext

/**
 * Extension function on Companion object defined in a separate file.
 * Changed from Int to Int? - this changes the JVM descriptor.
 */
internal fun PlayerDefine.State.Companion.toString(state: Int?): String {
    return "state=$state"
}
