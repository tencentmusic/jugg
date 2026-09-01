package com.sickworm.jugg.demo.testcase.membergenericsignature

class GenericEvent<T>(private val value: T) {

    fun observe(observer: (T) -> Unit) {
        observer(value)
    }
}
