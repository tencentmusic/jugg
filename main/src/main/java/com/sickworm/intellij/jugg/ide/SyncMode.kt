package com.sickworm.intellij.jugg.ide

enum class SyncMode(val modeName: String) {
    IFT("iFt"),
    RSYNC_SIMPLE("rsync_simple"),
    RSYNC("rsync"),
    ;

    val isRsync get() = this == RSYNC || this == RSYNC_SIMPLE

    val isRsyncSimple get() = this == RSYNC_SIMPLE
}