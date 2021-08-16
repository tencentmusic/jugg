package com.sickworm.intellij.jugg.toolWindow

import java.awt.event.MouseEvent
import java.awt.event.MouseListener

abstract class OnRightClickListener: MouseListener {
    override fun mouseClicked(e: MouseEvent) {
        if (e.isMetaDown) {
            onRightClick(e)
        }
    }

    override fun mousePressed(e: MouseEvent) {
    }

    override fun mouseReleased(e: MouseEvent) {
    }

    override fun mouseEntered(e: MouseEvent) {
    }

    override fun mouseExited(e: MouseEvent) {
    }

    abstract fun onRightClick(e: MouseEvent)
}