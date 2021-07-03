package com.sickworm.intellij.aidp

import java.io.File

class AidpException(msg: String): Exception(msg) {

    companion object {
        fun notAllCompiled(remainFiles: List<File>): AidpException {
            return AidpException("Can not deploy changes because not all files has been compiled.\nremaining files:\n$remainFiles")
        }
    }
}