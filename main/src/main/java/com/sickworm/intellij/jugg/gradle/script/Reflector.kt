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
            val getMethodName = if (propertyName.startsWith("is")) propertyName else "get${propertyName.camelCompat}"
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

        // Returns the raw constructed object (not wrapped in Reflector) to avoid constructing
        // Reflector() from a static companion context, which crashes Kotlin 1.5 (Gradle 7)
        // codegen when Reflector is a non-static inner class of the .kts script.
        fun newInstanceRaw(className: String, vararg args: Any): Any? {
            return doNewInstanceRaw(className, false, args.toList().toTypedArray())
        }

        fun newInstanceRawP(className: String, vararg args: Any): Any? {
            return doNewInstanceRaw(className, true, args.toList().toTypedArray())
        }

        private fun doNewInstanceRaw(className: String, isPrivate: Boolean, args: Array<Any>): Any? {
            try {
                val clazz = Class.forName(className)
                val argsType: Array<Class<*>> = args.map {
                    if (it is Reflector.Value) it.clazz else it::class.java
                }.toTypedArray()
                val constructor = if (isPrivate) {
                    clazz.getDeclaredConstructor(*argsType)
                } else {
                    clazz.getConstructor(*argsType)
                }
                constructor.isAccessible = true
                return constructor.newInstance(*args)
            } catch (e: Throwable) {
                println("Jugg: reflect new instance failed: $e")
                return null
            }
        }

    }
}

fun reflectorNewInstance(className: String, vararg args: Any): Reflector? {
    val raw = Reflector.newInstanceRaw(className, *args) ?: return null
    return Reflector(raw)
}

fun reflectorNewInstanceP(className: String, vararg args: Any): Reflector? {
    val raw = Reflector.newInstanceRawP(className, *args) ?: return null
    return Reflector(raw)
}

/**
 * Top-level factory for constructing Reflector instances from outside the Reflector class.
 *
 * On Kotlin 1.5 (Gradle 7), all top-level classes in a .kts script are non-static inner classes
 * of the script class. When a sibling inner class (e.g. GradleApplicationInjector) directly calls
 * `Reflector(value)`, the Kotlin 1.5 backend fails to emit the outer instance argument, causing
 * NoSuchMethodError at runtime. Routing construction through this top-level function ensures the
 * outer instance is captured correctly from the function's own closure.
 */
fun reflector(value: Any?): Reflector = Reflector(value)

operator fun Reflector?.get(propertyName: String): Reflector? {
    return this?.property(propertyName)
}
