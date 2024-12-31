package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.util.Key

interface IProcessHandler {

    var isCanceledByNextTask: Boolean
    val isCanceled: Boolean
    var cancelAction: (() -> Unit)?

    fun notifyTextAvailable(text: String, outputType: Key<*>)
    fun detachProcess()
    fun destroyProcess()

}