package com.sickworm.intellij.jugg.compiler.source.kotlin

import java.net.URL
import java.net.URLClassLoader

/**
 * PriorityURLClassLoader loads classes from its own URLs first,
 * then falls back to [lowPriorityParent] when unresolved.
 */
class PriorityURLClassLoader(urls: Array<URL>, private val lowPriorityParent: ClassLoader) : URLClassLoader(urls, null) {

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        // priority use class loader
        return try {
            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            lowPriorityParent.loadClass(name)
        }
    }
}
