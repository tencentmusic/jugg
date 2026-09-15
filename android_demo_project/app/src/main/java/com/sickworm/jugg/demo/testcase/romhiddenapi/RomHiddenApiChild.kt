package com.sickworm.jugg.demo.testcase.romhiddenapi

import com.rom.RomSoftApCallback

/**
 * Subclasses a ROM sdk class whose own supertype only exists in rom_framework.jar. Kotlin has to
 * resolve the whole hierarchy here, so it fails when the public SDK android.jar comes first on the
 * classpath and shadows the framework jar.
 */
class RomHiddenApiChild : RomSoftApCallback() {

    override fun onStateChanged(state: Int, failureReason: Int) {
    }
}
