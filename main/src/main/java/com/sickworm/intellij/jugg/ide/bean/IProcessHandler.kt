package com.sickworm.intellij.jugg.ide.bean

import com.intellij.openapi.util.Key

interface IProcessHandler {

    var isCanceledByNextTask: Boolean
    val isCanceled: Boolean
    var cancelAction: (() -> Unit)?

    fun notifyTextAvailable(text: String, outputType: Key<*>)
    fun detachProcess()
    fun destroyProcess()


    companion object {
        val DEFAULT = object : IProcessHandler {
            override var isCanceledByNextTask: Boolean = false
            override val isCanceled: Boolean = false
            override var cancelAction: (() -> Unit)? = null
            override fun notifyTextAvailable(text: String, outputType: Key<*>) = Unit
            override fun detachProcess() = Unit
            override fun destroyProcess() = Unit
        }
    }
}