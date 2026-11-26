package com.sickworm.intellij.jugg.ide.bean

import com.intellij.openapi.util.Key

/**
 * IProcessHandler for process output routing and cancellation handling.
 * Collaboration: Consumed by running-task/compile flows, with [DEFAULT] providing a no-op fallback implementation.
 * Data Contract: [DEFAULT] keeps cancellation flags unset and all lifecycle callbacks no-op.
 */
interface IProcessHandler {

    var isCanceledByNextTask: Boolean
    val isCanceled: Boolean
    val stdoutType: Key<*> get() = Key.create<Any>("Jugg stdout")
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
