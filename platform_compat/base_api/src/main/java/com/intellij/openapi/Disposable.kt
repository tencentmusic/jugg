package com.intellij.openapi

interface Disposable {
    fun dispose()

    interface Parent : Disposable {
        fun beforeTreeDispose()
    }
}