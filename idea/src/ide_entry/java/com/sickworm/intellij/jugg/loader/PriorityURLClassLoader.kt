package com.sickworm.intellij.jugg.loader

import java.net.URL
import java.net.URLClassLoader

class PriorityURLClassLoader(
    urls: Array<URL>,
    private val lowPriorityParent: ClassLoader,
    private val blackList: Set<String> = emptySet()
) : URLClassLoader(urls, null) {

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        if (blackList.contains(name)) {
            return lowPriorityParent.loadClass(name)
        }
        return try {
            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            lowPriorityParent.loadClass(name)
        }
    }
}