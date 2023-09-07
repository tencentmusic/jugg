package com.sickworm.intellij.jugg.deploy


inline val String.isOfficialClass: Boolean
    get() {
        if (startsWith("Ljava/")) {
            return true
        }
        if (startsWith("Landroid")) {
            if (startsWith("Landroid/")) {
                return true
            }
            if (startsWith("Landroidx/")) {
                return true
            }
        }
        if (startsWith("Lkotlin")) {
            if (startsWith("Lkotlin/")) {
                return true
            }
            if (startsWith("Lkotlinx/")) {
                return true
            }
        }
        return false
    }

inline val String.classSigName: String
    get() {
        return "L" + this.replace('.', '/') + ";"
    }

const val desugarDefaultInterfaceSuffix = "$-CC;"

inline val String.desugarDefaultInterfaceName: String
    get() {
        return this.substring(0, length - 1) + desugarDefaultInterfaceSuffix
    }


inline val String.interfaceNameFromDesugar: String
    get() {
        return substring(0, length - desugarDefaultInterfaceSuffix.length) + ";"
    }