package com.sickworm.intellij.jugg.gradle.script

class Reflector(val value: Any?) {

    val valueString: String? get() = value?.toString()

    /** get property */
    fun get(propertyName: String): Reflector? {
        value ?: return null
        try {
            val getMethodName = if (propertyName.startsWith("is")) propertyName else "get${propertyName.camel}"
            val method = value::class.java.getMethod(getMethodName)
            val result = method.invoke(value)
            return Reflector(result)
        } catch (e: Throwable) {
            println("Jugg: reflect get field failed: $e")
            return null
        }
    }

    fun getPrivateField(propertyName: String): Reflector? {
        value ?: return null
        try {
            val field = value::class.java.getDeclaredField(propertyName)
            field.isAccessible = true
            val result = field.get(value)
            return Reflector(result)
        } catch (e: Throwable) {
            println("Jugg: reflect get private field failed: $e")
            return null
        }
    }

    fun invoke(methodName: String, vararg args: Any): Reflector? {
        value ?: return null
        try {
            val argsType: Array<Class<*>> = args.map {
                if (it is Value) it.clazz else it::class.java
            }.toTypedArray()
            val argValue: Array<Any?> = args.map {
                if (it is Value) it.value else it
            }.toTypedArray()
            val method = value::class.java.getMethod(methodName, *argsType)
            val result = method.invoke(value, *argValue)
            return Reflector(result)
        } catch (e: Throwable) {
            return null
        }
    }

    class Value(val clazz: Class<*>, val value: Any?)

    companion object {

        private val String.camel: String get() {
            return this.replaceFirstChar { it.uppercaseChar() }
        }

        private fun String.replaceFirstChar(transform: (Char) -> Char): String {
            return if (isNotEmpty()) transform(this[0]) + substring(1) else this
        }

        private fun Char.uppercaseChar(): Char {
            @Suppress("DEPRECATION")
            return if (isLowerCase()) toUpperCase() else this
        }
    }
}

operator fun Reflector?.get(propertyName: String): Reflector? {
    return this?.get(propertyName)
}
