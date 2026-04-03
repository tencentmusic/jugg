package com.sickworm.jugg.demo.testcase.ktcompanionext

/**
 * Extension function on Companion object defined in a separate file.
 * Takes Int parameter - initial version.
 */
internal fun PlayerDefine.State.Companion.toString(state: Int): String {
    return "state=$state"
}
