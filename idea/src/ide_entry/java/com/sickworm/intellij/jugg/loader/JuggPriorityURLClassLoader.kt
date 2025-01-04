package com.sickworm.intellij.jugg.loader

import java.net.URL
import java.net.URLClassLoader

class JuggPriorityURLClassLoader(
    urls: Array<URL>,
    private val lowPriorityParent: ClassLoader,
    private val isInBlackList: (String) -> Boolean = { false }
) : URLClassLoader(urls, null) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isInBlackList(name)) {
            return lowPriorityParent.loadClass(name)
        }
        return try {
            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            lowPriorityParent.loadClass(name)
        }
    }
}