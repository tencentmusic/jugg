package com.sickworm.jugg.demo.testcase.appcomponentfactory

object TestInitialize {

    var objBeforeAttach: Any? = null
    var objAfterAttach: Any? = null

    fun initBeforeAttach() {
        objBeforeAttach = "Initialized_objBeforeAttach"
    }

    fun initAfterAttach() {
        objAfterAttach = "Initialized_objAfterAttach"
    }
}