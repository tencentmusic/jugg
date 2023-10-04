package com.sickworm.intellij.jugg.deploy

import org.jetbrains.kotlin.utils.addToStdlib.indexOfOrNull
import java.io.File


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

inline val String.isOfficialClassExceptAndroidX: Boolean
    get() {
        if (startsWith("Landroidx/")) {
            return false
        }
        return isOfficialClass
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


inline val String.interfaceNameFromDesugaredDefaultMethodClass: String
    get() {
        return substring(0, length - desugarDefaultInterfaceSuffix.length) + ";"
    }

inline val String.outerClassName: String
    get() {
        val endIndex = indexOfOrNull('$') ?: length
        return substring(0, endIndex)
    }

inline val String.classNameToPath: String
    get() {
        return if (startsWith("L")) {
            substring(1, length - 1) + ".class"
        } else {
            replace('.', '/') + ".class"
        }
    }

inline val String.packageNameToPath: String
    get() {
        return replace('.', File.separatorChar) + File.separatorChar
    }


// e.g. Landroid/support/v4/os/ResultReceiver$1;
// ->
// android.support.v4.os.ResultReceiver$1
inline val String.sigFormatToPackage get(): String {
    return this.asmSigFormat.replace('/', '.')
}

// e.g. Landroid/support/v4/os/ResultReceiver$1;
// ->
// android/support/v4/os/ResultReceiver$1
inline val String.asmSigFormat get(): String {
    return this.substring(1, this.length - 1)
}