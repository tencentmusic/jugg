package com.sickworm.intellij.jugg.platform

import java.lang.reflect.Proxy

private val proxy = Proxy.newProxyInstance(
    IPlatformApi::class.java.classLoader,
    arrayOf<Class<*>>(IPlatformApi::class.java)
) { _, method, args ->
    method.invoke(PlatformApi.impl, *(args ?: emptyArray()))
} as IPlatformApi

/**
 * PlatformApi facade that forwards [IPlatformApi] calls to the injected host implementation.
 * Collaboration: Used by core modules as a stable entry point while IDE/CMD/test layers provide [impl].
 * Data Contract: [impl] must be initialized before use; proxy forwarding preserves method signatures and arguments.
 */
object PlatformApi : IPlatformApi by proxy {
    lateinit var impl: IPlatformApi
}
