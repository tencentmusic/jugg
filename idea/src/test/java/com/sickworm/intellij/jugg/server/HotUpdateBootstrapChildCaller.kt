package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.loader.JuggHotUpdateBootstrap

/** Simulates a hot-update class calling the stable bootstrap API. */
class HotUpdateBootstrapChildCaller {

    fun activeJarFileNames(): Array<String> {
        return JuggHotUpdateBootstrap.activeJarFileNames
    }
}
