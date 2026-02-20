package com.sickworm.intellij.jugg.gradle.script

/**
 * Reflector wraps reflection-based read/invoke/new-instance operations with null-safe fallbacks.
 */
class Reflector(val value: Any?) {

    val valueString: String? get() = value?.toString()

    /** get property */
    fun property(propertyName: String): Reflector? {
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

    fun field(propertyName: String): Reflector? {
        return doGetField(propertyName, false)
    }

    fun fieldP(propertyName: String): Reflector? {
        return doGetField(propertyName, true)
    }

    private fun doGetField(fieldName: String, isPrivate: Boolean, clazzInput: Class<*>? = null): Reflector? {
        value ?: return null
        val clazz = clazzInput ?: value::class.java
        try {
            val field = if (isPrivate) {
                clazz.getDeclaredField(fieldName)
            } else {
                clazz.getField(fieldName)
            }
            field.isAccessible = true
            return Reflector(field.get(value))
        } catch (e: Throwable) {
            if (isPrivate) {
                if (e is NoSuchFieldError || e is NoSuchFieldException) {
                    if (clazz.superclass != Object::class.java) {
                        return doGetField(fieldName, true, clazz.superclass)
                    }
                }
            }
            println("Jugg: reflect get field failed: $e")
            return null
        }
    }


    fun invoke(methodName: String, vararg args: Any): Reflector? {
        return doInvoke(methodName, false, args.toList())
    }

    fun invokeP(methodName: String, vararg args: Any): Reflector? {
        return doInvoke(methodName, true, args.toList())
    }

    private fun doInvoke(methodName: String, isPrivate: Boolean, args: List<Any>): Reflector? {
        value ?: return null
        try {
            val argsType: Array<Class<*>> = args.map {
                if (it is Value) it.clazz else it::class.java
            }.toTypedArray()
            val argValue: Array<Any?> = args.map {
                if (it is Value) it.value else it
            }.toTypedArray()
            val method = if (isPrivate) {
                value::class.java.getDeclaredMethod(methodName, *argsType)
            } else {
                value::class.java.getMethod(methodName, *argsType)
            }
            val result = method.invoke(value, *argValue)
            return Reflector(result)
        } catch (e: Throwable) {
            println("Jugg: reflect invoke method failed for ${value::class.java}.${methodName}: $e")
            return null
        }
    }

    /**
     * Value binds an argument with an explicit JVM class when reflective type inference is ambiguous.
     */
    class Value(val clazz: Class<*>, val value: Any?)

    companion object {

        fun newInstance(className: String, vararg args: Any): Reflector? {
            return doNewInstance(className, false, args.toList().toTypedArray())
        }

        fun newInstanceP(className: String, vararg args: Any): Reflector? {
            return doNewInstance(className, true, args.toList().toTypedArray())
        }

        private fun doNewInstance(className: String, isPrivate: Boolean, args: Array<Any>): Reflector? {
            try {
                val clazz = Class.forName(className)
                val argsType: Array<Class<*>> = args.map {
                    if (it is Value) it.clazz else it::class.java
                }.toTypedArray()
                val constructor = if (isPrivate) {
                    clazz.getDeclaredConstructor(*argsType)
                } else {
                    clazz.getConstructor(*argsType)
                }
                constructor.isAccessible = true
                val result = constructor.newInstance(*args)
                return Reflector(result)
            } catch (e: Throwable) {
                println("Jugg: reflect new instance failed: $e")
                return null
            }
        }


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
    return this?.property(propertyName)
}
