package com.intellij.util.lang

import java.net.URL

class UrlClassLoader : ClassLoader() {

    var urls: List<URL> = emptyList()
}