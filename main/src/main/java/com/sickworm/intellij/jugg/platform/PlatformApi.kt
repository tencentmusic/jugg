package com.sickworm.intellij.jugg.platform

import java.lang.reflect.Proxy

private val proxy = Proxy.newProxyInstance(
    IPlatformApi::class.java.classLoader,
    arrayOf<Class<*>>(IPlatformApi::class.java)
) { _, method, args ->
    method.invoke(PlatformApi.impl, *(args ?: emptyArray()))
} as IPlatformApi

object PlatformApi : IPlatformApi by proxy {
    lateinit var impl: IPlatformApi
}