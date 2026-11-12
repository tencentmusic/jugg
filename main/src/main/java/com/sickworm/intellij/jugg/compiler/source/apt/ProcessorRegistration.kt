package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.source.apt.processors.KuiklyPageJuggAptProcessor
import com.sickworm.intellij.jugg.compiler.source.apt.processors.ButterKnifeJuggAptProcessor
import com.sickworm.intellij.jugg.compiler.source.apt.processors.ARouterJuggAptProcessor

object ProcessorRegistration {

    fun get(): List<IJuggAptProcessor> {
        return listOf(
            KuiklyPageJuggAptProcessor(),
            ARouterJuggAptProcessor(),
            ButterKnifeJuggAptProcessor(),
        )
    }
}
