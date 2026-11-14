package com.alibaba.android.arouter.ksp

import com.alibaba.android.arouter.facade.enums.TypeKind
import com.google.devtools.ksp.symbol.KSType

/**
 * Maps KSType to TypeKind ordinal for @Autowired parameter type tracking.
 * Mirrors the original Java TypeUtils logic.
 */
object TypeExchange {

    /**
     * Exchange KSType to TypeKind ordinal.
     *
     * @param type the resolved KSType of a field
     * @param supertypeNames set of all supertype qualified names for subtype checking
     * @return TypeKind ordinal value
     */
    fun exchange(type: KSType, supertypeNames: Set<String>): Int {
        val qualifiedName = type.declaration.qualifiedName?.asString() ?: return TypeKind.OBJECT.ordinal

        return when (qualifiedName) {
            // Primitives and their boxed types
            "kotlin.Boolean", "java.lang.Boolean" -> TypeKind.BOOLEAN.ordinal
            "kotlin.Byte", "java.lang.Byte" -> TypeKind.BYTE.ordinal
            "kotlin.Short", "java.lang.Short" -> TypeKind.SHORT.ordinal
            "kotlin.Int", "java.lang.Integer" -> TypeKind.INT.ordinal
            "kotlin.Long", "java.lang.Long" -> TypeKind.LONG.ordinal
            "kotlin.Char", "java.lang.Character" -> TypeKind.CHAR.ordinal
            "kotlin.Float", "java.lang.Float" -> TypeKind.FLOAT.ordinal
            "kotlin.Double", "java.lang.Double" -> TypeKind.DOUBLE.ordinal
            "kotlin.String", "java.lang.String" -> TypeKind.STRING.ordinal
            else -> {
                when {
                    Consts.PARCELABLE in supertypeNames -> TypeKind.PARCELABLE.ordinal
                    Consts.SERIALIZABLE in supertypeNames ||
                        "kotlin.io.Serializable" in supertypeNames -> TypeKind.SERIALIZABLE.ordinal
                    else -> TypeKind.OBJECT.ordinal
                }
            }
        }
    }

    /**
     * Collect all supertype qualified names for a KSType (including transitive).
     */
    fun collectSupertypeNames(type: KSType): Set<String> {
        val names = mutableSetOf<String>()
        val declaration = type.declaration
        if (declaration is com.google.devtools.ksp.symbol.KSClassDeclaration) {
            declaration.superTypes.forEach { ref ->
                val resolved = ref.resolve()
                val name = resolved.declaration.qualifiedName?.asString()
                if (name != null) {
                    names.add(name)
                    names.addAll(collectSupertypeNames(resolved))
                }
            }
        }
        return names
    }
}
