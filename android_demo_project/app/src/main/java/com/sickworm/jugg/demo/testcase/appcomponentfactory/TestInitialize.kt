package com.sickworm.jugg.demo.testcase.appcomponentfactory

import android.app.Application

object TestInitialize {

    var application: Application? = null
    var objBeforeAttach: Any? = null
    var objAfterAttach: Any? = null

    fun initBeforeAttach() {
        objBeforeAttach = "Initialized_objBeforeAttach"
    }

    fun initAfterAttach() {
        objAfterAttach = "Initialized_objAfterAttach"
    }

    fun describe(instance: Any?): String {
        if (instance == null) return "null"
        return "${instance.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(instance))}"
    }
}
