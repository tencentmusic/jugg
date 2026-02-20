package com.sickworm.intellij.jugg.ide.bean

/**
 * SyncMode supported file synchronization strategies.
 * Collaboration: Consumed by option parsing and deploy branches through [isRsync] and [isRsyncSimple].
 * Data Contract: [modeName] is the serialized identifier used in configuration payloads.
 */
enum class SyncMode(val modeName: String) {
    IFT("iFt"),
    RSYNC_SIMPLE("rsync_simple"),
    RSYNC("rsync"),
    ;

    val isRsync get() = this == RSYNC || this == RSYNC_SIMPLE

    val isRsyncSimple get() = this == RSYNC_SIMPLE

    override fun toString(): String {
        return modeName
    }
}
